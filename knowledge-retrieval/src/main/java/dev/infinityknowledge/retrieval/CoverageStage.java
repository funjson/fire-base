package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.retrieval.fusion.FusedCandidate;
import dev.infinityknowledge.spi.retrieval.CoverageCandidate;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import dev.infinityknowledge.spi.retrieval.CoverageJudgmentRequest;
import dev.infinityknowledge.spi.retrieval.CoverageJudgmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/**
 * 对“上一轮有限 Memory + 本轮新增候选”执行 Coverage Judge，并最多技术重试一次。
 */
final class CoverageStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(CoverageStage.class);
    private static final int MAXIMUM_TECHNICAL_ATTEMPTS = 2;

    private final CoverageJudge judge;
    private final Executor executor;
    private final Clock clock;
    private final Duration timeout;

    CoverageStage(CoverageJudge judge, Executor executor, Clock clock, Duration timeout) {
        this.judge = Objects.requireNonNull(judge, "judge must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("coverage timeout must be positive");
        }
    }

    /**
     * Judge 每轮重新选择有序保留集合；结果替换旧 Memory，不做累计分值。
     */
    Result evaluate(
            KnowledgeQuery query,
            List<FusedCandidate> previousMemory,
            List<FusedCandidate> currentCandidates,
            int memoryLimit,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos
    ) {
        Objects.requireNonNull(query, "query must not be null");
        Instant startedAt = clock.instant();
        List<FusedCandidate> workingSet = workingSet(previousMemory, currentCandidates);
        CoverageJudgmentRequest request = new CoverageJudgmentRequest(
                query.text(),
                query.retrievalTarget(),
                query.evidenceRequirements(),
                workingSet.stream().map(CoverageStage::toJudgeCandidate).toList(),
                memoryLimit
        );
        Throwable lastFailure = null;
        int requestCount = 0;
        for (int technicalAttempt = 0;
                technicalAttempt < MAXIMUM_TECHNICAL_ATTEMPTS;
                technicalAttempt++) {
            try {
                requestCount++;
                CoverageJudgmentResult judgment = invoke(request, requestDeadlineNanos);
                List<FusedCandidate> retained = applyJudgment(
                        judgment,
                        workingSet,
                        memoryLimit
                );
                steps.add(trace(
                        startedAt,
                        workingSet.size(),
                        retained.size(),
                        technicalAttempt == 0 ? "SUCCEEDED" : "RETRIED"
                ));
                if (technicalAttempt > 0) {
                    addWarning(warnings, "COVERAGE_JUDGE_RETRIED");
                }
                return Result.succeeded(judgment, retained, requestCount);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException(
                        "retrieval request interrupted during coverage judgment"
                );
                cancellation.initCause(interrupted);
                throw cancellation;
            } catch (RuntimeException | TimeoutException failure) {
                lastFailure = failure;
                if (requestDeadlineNanos - System.nanoTime() <= 0L) {
                    addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
                    break;
                }
            }
        }
        addWarning(warnings, "COVERAGE_JUDGE_UNAVAILABLE");
        steps.add(trace(startedAt, workingSet.size(), previousMemory.size(), "FAILED"));
        LOGGER.warn(
                "Coverage judge unavailable after technical retry: requestId={}, tenantId={}, failureType={}",
                query.requestId(),
                query.principal().tenantId().value(),
                lastFailure == null ? "Unknown" : lastFailure.getClass().getSimpleName()
        );
        return Result.failed(List.copyOf(previousMemory), requestCount);
    }

    private CoverageJudgmentResult invoke(
            CoverageJudgmentRequest request,
            long requestDeadlineNanos
    ) throws InterruptedException, TimeoutException {
        FutureTask<CoverageJudgmentResult> task = new FutureTask<>(() ->
                Objects.requireNonNull(judge.judge(request), "coverageJudge must not return null")
        );
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            throw new CoverageExecutionException("coverage execution was rejected", rejected);
        }
        long remaining = requestDeadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            task.cancel(true);
            throw new TimeoutException("request deadline elapsed before coverage judgment");
        }
        try {
            return task.get(
                    Math.min(remaining, timeout.toNanos()),
                    TimeUnit.NANOSECONDS
            );
        } catch (java.util.concurrent.TimeoutException timedOut) {
            task.cancel(true);
            throw new TimeoutException("coverage judgment timed out");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CoverageExecutionException("coverage judgment failed", cause);
        } catch (CancellationException cancelled) {
            throw new CoverageExecutionException("coverage judgment was cancelled", cancelled);
        }
    }

    private static List<FusedCandidate> workingSet(
            List<FusedCandidate> previousMemory,
            List<FusedCandidate> currentCandidates
    ) {
        Objects.requireNonNull(previousMemory, "previousMemory must not be null");
        Objects.requireNonNull(currentCandidates, "currentCandidates must not be null");
        Map<UUID, FusedCandidate> unique = new LinkedHashMap<>();
        previousMemory.forEach(value -> unique.put(value.representative().chunkId(), value));
        currentCandidates.forEach(value -> unique.put(value.representative().chunkId(), value));
        if (unique.size() > 200) {
            throw new IllegalStateException("coverage working set exceeds the hard limit of 200");
        }
        return List.copyOf(unique.values());
    }

    private static CoverageCandidate toJudgeCandidate(FusedCandidate value) {
        var candidate = value.representative();
        return new CoverageCandidate(
                candidate.chunkId(),
                candidate.spaceId(),
                candidate.title(),
                candidate.sectionPath(),
                candidate.content()
        );
    }

    private static List<FusedCandidate> applyJudgment(
            CoverageJudgmentResult judgment,
            List<FusedCandidate> workingSet,
            int memoryLimit
    ) {
        Set<UUID> allowed = workingSet.stream()
                .map(value -> value.representative().chunkId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (judgment.retainedCandidateIds().size() > memoryLimit
                || !allowed.containsAll(judgment.retainedCandidateIds())) {
            throw new SecurityException(
                    "coverage judge returned unknown candidates or exceeded memoryLimit"
            );
        }
        Map<UUID, FusedCandidate> byId = workingSet.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value.representative().chunkId(),
                        value -> value
                )
        );
        return judgment.retainedCandidateIds().stream().map(byId::get).toList();
    }

    private RetrievalStepTrace trace(
            Instant startedAt,
            int input,
            int output,
            String status
    ) {
        Duration value = Duration.between(startedAt, clock.instant());
        return new RetrievalStepTrace(
                "COVERAGE_CHECK",
                value.isNegative() ? Duration.ZERO : value,
                input,
                output,
                status
        );
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    /** 单轮 Coverage 的可信结果或技术失败。 */
    record Result(
            boolean checkFailed,
            double coverage,
            List<String> missingGaps,
            List<FusedCandidate> retainedCandidates,
            String reasonCode,
            int requestCount
    ) {
        Result {
            missingGaps = List.copyOf(missingGaps);
            retainedCandidates = List.copyOf(retainedCandidates);
            if (requestCount < 0 || requestCount > MAXIMUM_TECHNICAL_ATTEMPTS) {
                throw new IllegalArgumentException("coverage requestCount is out of range");
            }
        }

        static Result succeeded(
                CoverageJudgmentResult judgment,
                List<FusedCandidate> retained,
                int requestCount
        ) {
            return new Result(
                    false,
                    judgment.coverage(),
                    judgment.missingGaps(),
                    retained,
                    judgment.reasonCode(),
                    requestCount
            );
        }

        static Result failed(List<FusedCandidate> previousMemory, int requestCount) {
            return new Result(
                    true,
                    0.0D,
                    List.of(),
                    previousMemory,
                    "CHECK_FAILED",
                    requestCount
            );
        }
    }

    /** 将 checked 异常收敛为稳定的运行时技术失败。 */
    private static final class CoverageExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CoverageExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
