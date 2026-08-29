package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.retrieval.fusion.FusedCandidate;
import dev.infinityknowledge.retrieval.query.PlannedQuery;
import dev.infinityknowledge.spi.retrieval.CoverageCandidate;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanningRequest;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanningResult;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 执行一个已经由固定 Chain 选定的查询生成节点。 */
final class FeedbackPlanningStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(FeedbackPlanningStage.class);

    private final FeedbackQueryPlanner planner;
    private final Executor executor;
    private final Clock clock;
    private final Duration timeout;

    FeedbackPlanningStage(
            FeedbackQueryPlanner planner,
            Executor executor,
            Clock clock,
            Duration timeout
    ) {
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("feedback planner timeout must be positive");
        }
    }

    /**
     * 模型只能生成调用方指定类型的单个 Variant；失败或无有效增量时返回空。
     */
    Optional<PlannedQuery> plan(
            KnowledgeQuery query,
            QueryVariantKind strategy,
            List<String> missingGaps,
            List<FusedCandidate> evidenceMemory,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos
    ) {
        Instant startedAt = clock.instant();
        FeedbackQueryPlanningRequest request = new FeedbackQueryPlanningRequest(
                strategy,
                query.text(),
                query.retrievalTarget(),
                missingGaps,
                evidenceMemory.stream().map(FeedbackPlanningStage::candidate).toList()
        );
        FutureTask<FeedbackQueryPlanningResult> task = new FutureTask<>(() ->
                Objects.requireNonNull(
                        planner.plan(request),
                        "feedbackQueryPlanner must not return null"
                )
        );
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            return failed(query, strategy, steps, warnings, startedAt, rejected, "OVERLOADED");
        }
        long remaining = requestDeadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            task.cancel(true);
            addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            return failed(
                    query, strategy, steps, warnings, startedAt,
                    new TimeoutException("request deadline elapsed"), "TIMEOUT"
            );
        }
        try {
            FeedbackQueryPlanningResult result = task.get(
                    Math.min(remaining, timeout.toNanos()),
                    TimeUnit.NANOSECONDS
            );
            if (result.variant().kind() != strategy) {
                throw new SecurityException(
                        "feedback planner changed the selected optimization strategy"
                );
            }
            String generated = result.variant().text().replaceAll("\\s+", " ").strip();
            if (generated.equals(query.text().replaceAll("\\s+", " ").strip())) {
                steps.add(trace(startedAt, "NO_INCREMENT", 0));
                addWarning(warnings, "CHAIN_" + strategy.name() + "_NO_INCREMENT");
                return Optional.empty();
            }
            PlannedQuery planned = new PlannedQuery(
                    result.variant().id(),
                    result.variant().kind(),
                    generated,
                    result.provider(),
                    result.model()
            );
            steps.add(trace(startedAt, "SUCCEEDED", 1));
            return Optional.of(planned);
        } catch (TimeoutException timedOut) {
            task.cancel(true);
            return failed(query, strategy, steps, warnings, startedAt, timedOut, "TIMEOUT");
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            CancellationException cancellation = new CancellationException(
                    "retrieval request interrupted during feedback planning"
            );
            cancellation.initCause(interrupted);
            throw cancellation;
        } catch (ExecutionException failed) {
            return failed(
                    query, strategy, steps, warnings, startedAt, failed.getCause(), "FAILED"
            );
        } catch (CancellationException cancelled) {
            return failed(query, strategy, steps, warnings, startedAt, cancelled, "CANCELLED");
        }
    }

    private Optional<PlannedQuery> failed(
            KnowledgeQuery query,
            QueryVariantKind strategy,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            Instant startedAt,
            Throwable failure,
            String status
    ) {
        addWarning(warnings, "CHAIN_" + strategy.name() + "_UNAVAILABLE");
        steps.add(trace(startedAt, status, 0));
        LOGGER.warn(
                "Feedback query planning failed: strategy={}, requestId={}, tenantId={}, failureType={}",
                strategy.name(),
                query.requestId(),
                query.principal().tenantId().value(),
                failure == null ? "Unknown" : failure.getClass().getSimpleName()
        );
        return Optional.empty();
    }

    private RetrievalStepTrace trace(Instant startedAt, String status, int outputCount) {
        Duration value = Duration.between(startedAt, clock.instant());
        return new RetrievalStepTrace(
                "CHAIN_QUERY_GENERATION",
                value.isNegative() ? Duration.ZERO : value,
                1,
                outputCount,
                status
        );
    }

    private static CoverageCandidate candidate(FusedCandidate value) {
        var source = value.representative();
        return new CoverageCandidate(
                source.chunkId(),
                source.spaceId(),
                source.title(),
                source.sectionPath(),
                source.content()
        );
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }
}
