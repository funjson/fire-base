package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.retrieval.RetrievalSpaceCatalog;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingCandidate;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingRequest;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 加载服务端已授权的 Space 摘要，并让模型只对该有限集合排序。
 *
 * <p>模型失败时保留目录的稳定顺序。执行结果始终只是有序列表；当前 Space 由调用方用
 * 下标访问，不创建额外的激活状态。</p>
 */
final class SpaceRoutingStage {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpaceRoutingStage.class);

    private final RetrievalSpaceCatalog catalog;
    private final SpaceRouter router;
    private final Executor executor;
    private final Clock clock;
    private final Duration timeout;

    SpaceRoutingStage(
            RetrievalSpaceCatalog catalog,
            SpaceRouter router,
            Executor executor,
            Clock clock,
            Duration timeout
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("space routing timeout must be positive");
        }
    }

    /**
     * 返回模型排序后的授权 Space；单个 Space 不调用模型。
     */
    Result route(
            KnowledgeQuery query,
            AccessScope scope,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos
    ) {
        Instant startedAt = clock.instant();
        List<SpaceRoutingCandidate> candidates = stableCandidates(scope);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("authorized scope contains no active retrieval space");
        }
        if (candidates.size() == 1) {
            Result result = new Result(
                    List.of(candidates.getFirst().spaceId()),
                    "deterministic",
                    "single-space",
                    false
            );
            steps.add(trace(startedAt, candidates.size(), result.spaceIds().size(), "SUCCEEDED"));
            return result;
        }

        FutureTask<SpaceRoutingResult> task = new FutureTask<>(() ->
                Objects.requireNonNull(
                        router.rank(new SpaceRoutingRequest(query.text(), candidates)),
                        "spaceRouter must not return null"
                )
        );
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            addWarning(warnings, "SPACE_ROUTER_OVERLOADED");
            return fallback(query, candidates, steps, warnings, startedAt, rejected);
        }
        long remaining = requestDeadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            task.cancel(true);
            addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            addWarning(warnings, "SPACE_ROUTER_TIMEOUT");
            return fallback(
                    query, candidates, steps, warnings, startedAt,
                    new TimeoutException("request deadline elapsed before space routing")
            );
        }
        boolean requestDeadlineBound = remaining <= timeout.toNanos();
        try {
            SpaceRoutingResult routed = task.get(
                    Math.min(remaining, timeout.toNanos()),
                    TimeUnit.NANOSECONDS
            );
            validatePermutation(candidates, routed.orderedSpaceIds());
            Result result = new Result(
                    routed.orderedSpaceIds(), routed.provider(), routed.model(), false
            );
            steps.add(trace(startedAt, candidates.size(), result.spaceIds().size(), "SUCCEEDED"));
            return result;
        } catch (TimeoutException timedOut) {
            task.cancel(true);
            if (requestDeadlineBound) {
                addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            }
            addWarning(warnings, "SPACE_ROUTER_TIMEOUT");
            return fallback(query, candidates, steps, warnings, startedAt, timedOut);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            CancellationException cancellation = new CancellationException(
                    "retrieval request interrupted during space routing"
            );
            cancellation.initCause(interrupted);
            throw cancellation;
        } catch (ExecutionException failed) {
            return fallback(query, candidates, steps, warnings, startedAt, failed.getCause());
        } catch (CancellationException cancelled) {
            return fallback(query, candidates, steps, warnings, startedAt, cancelled);
        }
    }

    private List<SpaceRoutingCandidate> stableCandidates(AccessScope scope) {
        List<SpaceRoutingCandidate> loaded = new ArrayList<>(List.copyOf(
                Objects.requireNonNull(
                        catalog.findAllowed(scope.tenantId(), scope.spaceIds()),
                        "retrievalSpaceCatalog must not return null"
                )
        ));
        if (loaded.stream().anyMatch(candidate -> !scope.allowsSpace(candidate.spaceId()))) {
            throw new SecurityException("space catalog returned a space outside authorized scope");
        }
        Set<KnowledgeSpaceId> unique = new HashSet<>();
        if (loaded.stream().anyMatch(candidate -> !unique.add(candidate.spaceId()))) {
            throw new IllegalStateException("space catalog returned duplicate spaces");
        }
        loaded.sort(java.util.Comparator.comparing(value -> value.spaceId().value()));
        return List.copyOf(loaded);
    }

    private void validatePermutation(
            List<SpaceRoutingCandidate> allowed,
            List<KnowledgeSpaceId> ordered
    ) {
        Set<KnowledgeSpaceId> expected = allowed.stream()
                .map(SpaceRoutingCandidate::spaceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (ordered.size() != expected.size() || !expected.equals(new HashSet<>(ordered))) {
            throw new SecurityException(
                    "space router result must be an exact permutation of authorized spaces"
            );
        }
    }

    private Result fallback(
            KnowledgeQuery query,
            List<SpaceRoutingCandidate> candidates,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            Instant startedAt,
            Throwable failure
    ) {
        addWarning(warnings, "SPACE_ROUTER_UNAVAILABLE");
        List<KnowledgeSpaceId> stable = candidates.stream()
                .map(SpaceRoutingCandidate::spaceId)
                .toList();
        steps.add(trace(startedAt, candidates.size(), stable.size(), "DEGRADED"));
        LOGGER.warn(
                "Space router unavailable; using stable catalog order: requestId={}, tenantId={}, failureType={}",
                query.requestId(), query.principal().tenantId().value(),
                failure == null ? "Unknown" : failure.getClass().getSimpleName()
        );
        return new Result(stable, "deterministic", "catalog-order", true);
    }

    private RetrievalStepTrace trace(
            Instant startedAt,
            int inputCount,
            int outputCount,
            String status
    ) {
        Duration duration = Duration.between(startedAt, clock.instant());
        return new RetrievalStepTrace(
                "SPACE_ROUTING",
                duration.isNegative() ? Duration.ZERO : duration,
                inputCount,
                outputCount,
                status
        );
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    /** 模型产生的有序 Space 列表和安全模型标识。 */
    record Result(
            List<KnowledgeSpaceId> spaceIds,
            String provider,
            String model,
            boolean degraded
    ) {
        Result {
            spaceIds = List.copyOf(spaceIds);
        }
    }
}
