package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.retrieval.query.PlannedQuery;
import dev.infinityknowledge.retrieval.query.QueryOptimizationPlan;
import dev.infinityknowledge.retrieval.query.ResolvedConstraints;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansion;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionRequest;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionResult;
import dev.infinityknowledge.spi.retrieval.TerminologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 生成固定为 Q0 加可选单个 Q1 的首轮查询优化计划。
 *
 * <p>Q1 只能来自当前 Space 配置选择的受控术语资源。该阶段不调用通用 LLM
 * Query Planner，也不产生多个同义改写；资源不可用、超时或没有有效增量时稳定保留 Q0。</p>
 */
final class QueryPlanningStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueryPlanningStage.class);

    private final TerminologyService terminologyService;
    private final Executor executor;
    private final Clock clock;
    private final Duration timeout;

    QueryPlanningStage(
            TerminologyService terminologyService,
            Executor executor,
            Clock clock,
            Duration timeout
    ) {
        this.terminologyService = Objects.requireNonNull(
                terminologyService,
                "terminologyService must not be null"
        );
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    /**
     * 按物化首轮配置读取术语资源；无论外部结果如何都只可能输出 Q0 或 Q0+Q1。
     */
    Result planWithFallback(
            KnowledgeQuery query,
            QueryPlan plan,
            ResolvedConstraints resolvedConstraints,
            RetrievalConfiguration.FirstRound firstRound,
            int maximumVariantsPerAttempt,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos
    ) {
        Objects.requireNonNull(firstRound, "firstRound must not be null");
        Objects.requireNonNull(resolvedConstraints, "resolvedConstraints must not be null");
        if (maximumVariantsPerAttempt < 1) {
            throw new IllegalArgumentException("maximumVariantsPerAttempt must be positive");
        }
        Instant startedAt = clock.instant();
        QueryOptimizationPlan originalOnly = originalOnly(plan, resolvedConstraints);
        if (!firstRound.termExpansionEnabled() || maximumVariantsPerAttempt < 2) {
            steps.add(new RetrievalStepTrace(
                    "QUERY_PLANNING", duration(startedAt), 1, 1, "SUCCEEDED"
            ));
            return new Result(originalOnly, 0);
        }

        TerminologyExpansionRequest request = new TerminologyExpansionRequest(
                plan.normalizedQuery(),
                firstRound.terminologyResourceId(),
                firstRound.maximumExpansionTerms()
        );
        FutureTask<TerminologyExpansionResult> task = new FutureTask<>(() ->
                Objects.requireNonNull(
                        terminologyService.expand(request),
                        "terminologyService must not return null"
                )
        );
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            addWarning(warnings, "TERMINOLOGY_SERVICE_OVERLOADED");
            return new Result(
                    fallback(
                            query,
                            plan,
                            resolvedConstraints,
                            steps,
                            warnings,
                            startedAt,
                            rejected
                    ),
                    0
            );
        }

        long remaining = requestDeadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            task.cancel(true);
            addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            addWarning(warnings, "TERMINOLOGY_SERVICE_TIMEOUT");
            return new Result(
                    fallback(
                            query,
                            plan,
                            resolvedConstraints,
                            steps,
                            warnings,
                            startedAt,
                            new TimeoutException(
                                    "request deadline elapsed before terminology lookup"
                            )
                    ),
                    1
            );
        }
        long budget = timeout.toNanos();
        boolean requestDeadlineIsBound = remaining <= budget;
        try {
            TerminologyExpansionResult result = task.get(
                    Math.min(remaining, budget),
                    TimeUnit.NANOSECONDS
            );
            QueryOptimizationPlan planned = boundedPlan(
                    plan.normalizedQuery(),
                    resolvedConstraints,
                    request,
                    result
            );
            steps.add(new RetrievalStepTrace(
                    "QUERY_PLANNING",
                    duration(startedAt),
                    1,
                    planned.variants().size(),
                    "SUCCEEDED"
            ));
            return new Result(planned, 1);
        } catch (TimeoutException timedOut) {
            task.cancel(true);
            if (requestDeadlineIsBound) {
                addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            }
            addWarning(warnings, "TERMINOLOGY_SERVICE_TIMEOUT");
            return new Result(
                    fallback(
                            query,
                            plan,
                            resolvedConstraints,
                            steps,
                            warnings,
                            startedAt,
                            timedOut
                    ),
                    1
            );
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            CancellationException cancellation = new CancellationException(
                    "retrieval request interrupted during terminology lookup"
            );
            cancellation.initCause(interrupted);
            throw cancellation;
        } catch (ExecutionException failed) {
            return new Result(
                    fallback(
                            query,
                            plan,
                            resolvedConstraints,
                            steps,
                            warnings,
                            startedAt,
                            failed.getCause()
                    ),
                    1
            );
        } catch (CancellationException cancelled) {
            return new Result(
                    fallback(
                            query,
                            plan,
                            resolvedConstraints,
                            steps,
                            warnings,
                            startedAt,
                            cancelled
                    ),
                    1
            );
        } catch (RuntimeException invalidResult) {
            return new Result(
                    fallback(
                            query,
                            plan,
                            resolvedConstraints,
                            steps,
                            warnings,
                            startedAt,
                            invalidResult
                    ),
                    1
            );
        }
    }

    /** 外部服务必须证明词数受限，并且 Q1 与 Q0 存在实质文本增量。 */
    private QueryOptimizationPlan boundedPlan(
            String originalQuery,
            ResolvedConstraints resolvedConstraints,
            TerminologyExpansionRequest request,
            TerminologyExpansionResult result
    ) {
        if (result.expansion().isEmpty()) {
            return QueryOptimizationPlan.firstRound(
                    resolvedConstraints,
                    List.of(PlannedQuery.original(originalQuery))
            );
        }
        TerminologyExpansion expansion = result.expansion().orElseThrow();
        if (expansion.appliedTerms().size() > request.maximumExpansionTerms()) {
            throw new IllegalArgumentException(
                    "terminology result exceeds maximumExpansionTerms"
            );
        }
        String expanded = expansion.expandedQuery().replaceAll("\\s+", " ").strip();
        if (expanded.equals(originalQuery)) {
            return QueryOptimizationPlan.firstRound(
                    resolvedConstraints,
                    List.of(PlannedQuery.original(originalQuery))
            );
        }
        return QueryOptimizationPlan.firstRound(resolvedConstraints, List.of(
                PlannedQuery.original(originalQuery),
                new PlannedQuery(
                        "q1",
                        QueryVariantKind.TERM_EXPANSION,
                        expanded,
                        result.provider(),
                        result.resourceVersion()
                )
        ));
    }

    private QueryOptimizationPlan fallback(
            KnowledgeQuery query,
            QueryPlan plan,
            ResolvedConstraints resolvedConstraints,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            Instant startedAt,
            Throwable failure
    ) {
        addWarning(warnings, "TERMINOLOGY_SERVICE_UNAVAILABLE");
        steps.add(new RetrievalStepTrace(
                "QUERY_PLANNING", duration(startedAt), 1, 1, "DEGRADED"
        ));
        LOGGER.warn(
                "Terminology service unavailable; retaining Q0: requestId={}, tenantId={}, failureType={}",
                query.requestId(),
                query.principal().tenantId().value(),
                failure == null ? "Unknown" : failure.getClass().getSimpleName()
        );
        return originalOnly(plan, resolvedConstraints);
    }

    private QueryOptimizationPlan originalOnly(
            QueryPlan plan,
            ResolvedConstraints resolvedConstraints
    ) {
        return QueryOptimizationPlan.firstRound(
                resolvedConstraints,
                List.of(PlannedQuery.original(plan.normalizedQuery()))
        );
    }

    private Duration duration(Instant startedAt) {
        Duration value = Duration.between(startedAt, clock.instant());
        return value.isNegative() ? Duration.ZERO : value;
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    /**
     * 首轮计划及真实提交给术语端口的调用次数；调用失败或无增量仍计一次。
     */
    record Result(QueryOptimizationPlan plan, int terminologyCallCount) {
        Result {
            Objects.requireNonNull(plan, "plan must not be null");
            if (terminologyCallCount < 0 || terminologyCallCount > 1) {
                throw new IllegalArgumentException(
                        "terminologyCallCount must be zero or one"
                );
            }
        }
    }
}
