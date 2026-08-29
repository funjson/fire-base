package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.retrieval.fusion.FusedCandidate;
import dev.infinityknowledge.spi.retrieval.RerankResult;
import dev.infinityknowledge.spi.retrieval.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 在有限候选集上执行模型精排，并保留超时和失败时的 RRF 稳定回退。
 */
final class RerankStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(RerankStage.class);

    private final Reranker reranker;
    private final Executor executor;
    private final Clock clock;
    private final Duration timeout;

    RerankStage(Reranker reranker, Executor executor, Clock clock, Duration timeout) {
        this.reranker = Objects.requireNonNull(reranker, "reranker must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    Result rerankWithFallback(
            KnowledgeQuery query,
            String rerankQuery,
            List<FusedCandidate> candidates,
            boolean enabled,
            int candidateLimit,
            int outputLimit,
            Consumer<List<RetrievalCandidate>> authorizationVerifier,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos
    ) {
        Objects.requireNonNull(rerankQuery, "rerankQuery must not be null");
        if (rerankQuery.isBlank()) {
            throw new IllegalArgumentException("rerankQuery must not be blank");
        }
        Objects.requireNonNull(authorizationVerifier, "authorizationVerifier must not be null");
        if (candidateLimit < 1 || outputLimit < 1 || outputLimit > candidateLimit) {
            throw new IllegalArgumentException(
                    "rerank limits must be positive and outputLimit must not exceed candidateLimit"
            );
        }
        Instant startedAt = clock.instant();
        List<FusedCandidate> boundedCandidates = candidates.stream()
                .limit(candidateLimit)
                .toList();
        int boundedOutput = Math.min(outputLimit, query.topK());
        if (!enabled) {
            List<FusedCandidate> bypassed = boundedCandidates.stream()
                    .limit(boundedOutput)
                    .toList();
            steps.add(new RetrievalStepTrace(
                    "RERANK",
                    duration(startedAt),
                    candidates.size(),
                    bypassed.size(),
                    "BYPASSED"
            ));
            return new Result(bypassed, false, false, 0, "DISABLED");
        }
        if (boundedCandidates.isEmpty()) {
            steps.add(new RetrievalStepTrace(
                    "RERANK", duration(startedAt), 0, 0, "SKIPPED"
            ));
            return new Result(List.of(), false, false, 0, "EMPTY_INPUT");
        }
        FutureTask<AppliedRerank> task = new FutureTask<>(() -> rerank(
                rerankQuery, boundedCandidates, boundedOutput, authorizationVerifier
        ));
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            addWarning(warnings, "RERANKER_OVERLOADED");
            return fallback(query, boundedCandidates, boundedOutput, steps, warnings, startedAt, rejected);
        }
        long remaining = requestDeadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            task.cancel(true);
            addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            addWarning(warnings, "RERANKER_TIMEOUT");
            return fallback(query, boundedCandidates, boundedOutput, steps, warnings, startedAt,
                    new TimeoutException("request deadline elapsed before reranking"));
        }
        long budget = timeout.toNanos();
        boolean requestDeadlineIsBound = remaining <= budget;
        try {
            AppliedRerank result = task.get(Math.min(remaining, budget), TimeUnit.NANOSECONDS);
            steps.add(new RetrievalStepTrace(
                    "RERANK", duration(startedAt), boundedCandidates.size(),
                    result.candidates().size(), "SUCCEEDED"
            ));
            if (result.scoredCandidateCount() > 0
                    && result.scoredCandidateCount() < result.candidates().size()) {
                addWarning(warnings, "RERANKER_PARTIAL");
            }
            return new Result(
                    result.candidates(),
                    true,
                    false,
                    result.scoredCandidateCount(),
                    result.reasonCode()
            );
        } catch (TimeoutException timedOut) {
            task.cancel(true);
            if (requestDeadlineIsBound) {
                addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            }
            addWarning(warnings, "RERANKER_TIMEOUT");
            return fallback(query, boundedCandidates, boundedOutput, steps, warnings, startedAt, timedOut);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            CancellationException cancellation = new CancellationException(
                    "retrieval request interrupted during reranking"
            );
            cancellation.initCause(interrupted);
            throw cancellation;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IncompleteRerankOutputException) {
                addWarning(warnings, "RERANKER_INCOMPLETE_OUTPUT");
            }
            return fallback(
                    query,
                    boundedCandidates,
                    boundedOutput,
                    steps,
                    warnings,
                    startedAt,
                    cause
            );
        } catch (CancellationException cancelled) {
            return fallback(query, boundedCandidates, boundedOutput, steps, warnings, startedAt, cancelled);
        }
    }

    private Result fallback(
            KnowledgeQuery query, List<FusedCandidate> candidates,
            int outputLimit,
            List<RetrievalStepTrace> steps, List<String> warnings,
            Instant startedAt, Throwable failure
    ) {
        List<FusedCandidate> fallback = candidates.stream().limit(outputLimit).toList();
        addWarning(warnings, "RERANKER_UNAVAILABLE");
        steps.add(new RetrievalStepTrace(
                "RERANK", duration(startedAt), candidates.size(), fallback.size(), "DEGRADED"
        ));
        LOGGER.warn(
                "Reranker unavailable; using fused order: requestId={}, tenantId={}, failureType={}",
                query.requestId(), query.principal().tenantId().value(),
                failure == null ? "Unknown" : failure.getClass().getSimpleName()
        );
        return new Result(fallback, true, true, 0, "RRF_FALLBACK");
    }

    private AppliedRerank rerank(
            String query,
            List<FusedCandidate> candidates,
            int limit,
            Consumer<List<RetrievalCandidate>> authorizationVerifier
    ) {
        if (candidates.isEmpty()) {
            return new AppliedRerank(List.of(), 0, "EMPTY_INPUT");
        }
        List<RetrievalCandidate> representatives = candidates.stream()
                .map(FusedCandidate::representative).toList();
        int boundedLimit = Math.min(limit, representatives.size());
        RerankResult result = Objects.requireNonNull(
                reranker.rerank(query, representatives, boundedLimit), "reranker must not return null"
        );
        List<RetrievalCandidate> reranked = result.orderedCandidates();
        if (reranked.size() != boundedLimit
                || result.scoredCandidateCount() != boundedLimit) {
            throw new IncompleteRerankOutputException();
        }
        Set<RetrievalCandidate> allowed = new HashSet<>(representatives);
        Set<RetrievalCandidate> unique = new HashSet<>(reranked);
        if (reranked.size() > boundedLimit || unique.size() != reranked.size()
                || !allowed.containsAll(reranked)) {
            throw new SecurityException("reranker returned unknown or duplicate candidates");
        }
        authorizationVerifier.accept(reranked);
        Map<UUID, FusedCandidate> byChunk = candidates.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value.representative().chunkId(), value -> value
                )
        );
        List<FusedCandidate> applied = reranked.stream().map(value -> {
            FusedCandidate fused = byChunk.get(value.chunkId());
            Double modelScore = result.scoresByChunk().get(value.chunkId());
            // 原始模型分数只作为本次精排诊断事实，不覆盖 Evidence 展示的 RRF 相对强度。
            return fused.withRerank(modelScore, result.reasonCode());
        }).toList();
        return new AppliedRerank(
                applied,
                result.scoredCandidateCount(),
                result.reasonCode()
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

    /** 保存已经应用顺序且保留精排诊断事实的结果。 */
    private record AppliedRerank(
            List<FusedCandidate> candidates,
            int scoredCandidateCount,
            String reasonCode
    ) {
    }

    /** 标记模型未完整处理约定输出窗口；异常不携带查询或候选内容，避免进入日志。 */
    private static final class IncompleteRerankOutputException
            extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private IncompleteRerankOutputException() {
            super("reranker returned an incomplete candidate window");
        }
    }

    /** 精排结果显式区分禁用、真实执行和整批 RRF 回退。 */
    record Result(
            List<FusedCandidate> candidates,
            boolean executed,
            boolean fallback,
            int scoredCandidateCount,
            String reasonCode
    ) {
        Result {
            candidates = List.copyOf(candidates);
            if (scoredCandidateCount < 0 || scoredCandidateCount > candidates.size()) {
                throw new IllegalArgumentException(
                        "scoredCandidateCount must be within candidate result size"
                );
            }
            reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        }
    }
}
