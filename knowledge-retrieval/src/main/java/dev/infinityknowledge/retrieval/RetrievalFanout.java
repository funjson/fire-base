package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.retrieval.fusion.RankedCandidateList;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 分配原查询优先的候选预算，并执行可取消的多通道并行召回。
 */
final class RetrievalFanout {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetrievalFanout.class);

    private final Map<RetrievalChannel, Retriever> retrievers;
    private final Executor executor;
    private final Clock clock;
    private final Duration channelTimeout;

    RetrievalFanout(
            List<Retriever> retrievers,
            Executor executor,
            Clock clock,
            Duration channelTimeout
    ) {
        this.retrievers = indexRetrievers(retrievers);
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.channelTimeout = Objects.requireNonNull(channelTimeout, "channelTimeout must not be null");
    }

    Result retrieveAll(
            List<RetrievalBranchRequest> requests,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos
    ) {
        List<ChannelTask> tasks = requests.stream()
                .map(this::submit)
                .toList();
        List<RankedCandidateList> rankedLists = new ArrayList<>();
        List<BranchOutcome> outcomes = new ArrayList<>();
        try {
            for (ChannelTask task : tasks) {
                ChannelResult result = await(task, requestDeadlineNanos);
                steps.add(result.trace());
                if (result.failure()) {
                    addFailureWarning(warnings, result);
                } else {
                    rankedLists.add(result.rankedList());
                }
                outcomes.add(new BranchOutcome(
                        result.branch(),
                        result.rankedList(),
                        result.trace().status(),
                        result.trace().duration()
                ));
            }
        } catch (InterruptedException interrupted) {
            tasks.stream().map(ChannelTask::future).filter(future -> !future.isDone())
                    .forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            CancellationException cancellation = new CancellationException("retrieval request interrupted");
            cancellation.initCause(interrupted);
            throw cancellation;
        }
        return new Result(rankedLists, outcomes);
    }

    private ChannelTask submit(RetrievalBranchRequest branch) {
        RetrievalChannel channel = branch.request().plan().channels().iterator().next();
        Instant startedAt = clock.instant();
        long deadlineNanos = System.nanoTime() + channelTimeout.toNanos();
        FutureTask<ChannelResult> future = new FutureTask<>(() -> retrieve(branch));
        try {
            executor.execute(future);
        } catch (RejectedExecutionException rejected) {
            LOGGER.warn("Retriever execution rejected: channel={}, requestId={}, tenantId={}",
                    channel.name(), branch.request().query().requestId(),
                    branch.request().query().principal().tenantId().value());
            future = new FutureTask<>(() -> ChannelResult.failed(
                    branch, duration(startedAt), "REJECTED"
            ));
            future.run();
        }
        return new ChannelTask(branch, future, startedAt, deadlineNanos);
    }

    private ChannelResult await(ChannelTask task, long requestDeadlineNanos)
            throws InterruptedException {
        long effectiveDeadline = Math.min(requestDeadlineNanos, task.deadlineNanos());
        long remaining = effectiveDeadline - System.nanoTime();
        if (remaining <= 0L && !task.future().isDone()) {
            task.future().cancel(true);
            return timeout(task, requestDeadlineNanos <= task.deadlineNanos());
        }
        try {
            return task.future().get(Math.max(0L, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            task.future().cancel(true);
            return timeout(task, requestDeadlineNanos <= task.deadlineNanos());
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            LOGGER.warn(
                    "Retriever task failed unexpectedly: channel={}, failureType={}",
                    task.channel().name(),
                    cause == null ? "Unknown" : cause.getClass().getSimpleName()
            );
            return ChannelResult.failed(task.branch(), duration(task.startedAt()), "FAILED");
        } catch (CancellationException cancelled) {
            return ChannelResult.failed(task.branch(), duration(task.startedAt()), "CANCELLED");
        }
    }

    private ChannelResult retrieve(RetrievalBranchRequest branch) {
        RetrievalRequest request = branch.request();
        RetrievalChannel channel = request.plan().channels().iterator().next();
        Instant startedAt = clock.instant();
        Retriever retriever = retrievers.get(channel);
        if (retriever == null) {
            return ChannelResult.failed(branch, duration(startedAt), "NOT_CONFIGURED");
        }
        try {
            List<RetrievalCandidate> result = List.copyOf(Objects.requireNonNull(
                    retriever.retrieve(request), "retriever must not return null"
            )).stream().limit(request.plan().candidateLimit()).toList();
            return ChannelResult.succeeded(branch, result, duration(startedAt));
        } catch (RuntimeException failed) {
            LOGGER.warn(
                    "Retriever failed: channel={}, requestId={}, tenantId={}, failureType={}",
                    channel.name(),
                    request.query().requestId(),
                    request.query().principal().tenantId().value(),
                    failed.getClass().getSimpleName()
            );
            return ChannelResult.failed(branch, duration(startedAt), "FAILED");
        }
    }

    private ChannelResult timeout(ChannelTask task, boolean requestDeadline) {
        return ChannelResult.failed(task.branch(), duration(task.startedAt()),
                requestDeadline ? "DEADLINE_EXCEEDED" : "TIMEOUT");
    }

    private Duration duration(Instant startedAt) {
        Duration value = Duration.between(startedAt, clock.instant());
        return value.isNegative() ? Duration.ZERO : value;
    }

    private static Map<RetrievalChannel, Retriever> indexRetrievers(List<Retriever> values) {
        Objects.requireNonNull(values, "retrievers must not be null");
        EnumMap<RetrievalChannel, Retriever> indexed = new EnumMap<>(RetrievalChannel.class);
        for (Retriever retriever : values) {
            Objects.requireNonNull(retriever, "retriever must not be null");
            Retriever previous = indexed.putIfAbsent(retriever.channel(), retriever);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate retriever channel " + retriever.channel());
            }
        }
        return Map.copyOf(indexed);
    }

    private static void addFailureWarning(List<String> warnings, ChannelResult result) {
        String prefix = "RETRIEVER_" + result.channel().name();
        switch (result.trace().status()) {
            case "DEADLINE_EXCEEDED" -> {
                addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
                addWarning(warnings, prefix + "_TIMEOUT");
            }
            case "TIMEOUT" -> addWarning(warnings, prefix + "_TIMEOUT");
            case "REJECTED" -> addWarning(warnings, prefix + "_OVERLOADED");
            default -> addWarning(warnings, prefix + "_UNAVAILABLE");
        }
    }

    private static void addWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private record ChannelTask(
            RetrievalBranchRequest branch,
            FutureTask<ChannelResult> future,
            Instant startedAt,
            long deadlineNanos
    ) {
        private RetrievalChannel channel() {
            return branch.request().plan().channels().iterator().next();
        }
    }

    private record ChannelResult(
            RetrievalBranchRequest branch,
            RankedCandidateList rankedList,
            RetrievalStepTrace trace,
            boolean failure
    ) {
        private RetrievalChannel channel() {
            return branch.request().plan().channels().iterator().next();
        }

        private static ChannelResult succeeded(
                RetrievalBranchRequest branch,
                List<RetrievalCandidate> candidates,
                Duration duration
        ) {
            RetrievalChannel channel = branch.request().plan().channels().iterator().next();
            return new ChannelResult(
                    branch,
                    new RankedCandidateList(
                            branch.branchId(), channel, branch.rrfWeight(), candidates
                    ),
                    new RetrievalStepTrace(
                            "RETRIEVER_" + channel.name(),
                            duration,
                            1,
                            candidates.size(),
                            "SUCCEEDED"
                    ),
                    false
            );
        }

        private static ChannelResult failed(
                RetrievalBranchRequest branch, Duration duration, String status
        ) {
            RetrievalChannel channel = branch.request().plan().channels().iterator().next();
            return new ChannelResult(
                    branch,
                    null,
                    new RetrievalStepTrace(
                            "RETRIEVER_" + channel.name(), duration, 1, 0, status
                    ),
                    true
            );
        }
    }

    /** 保存全部分支终态，失败分支也不能从逐层观测中消失。 */
    record Result(List<RankedCandidateList> rankedLists, List<BranchOutcome> outcomes) {
        Result {
            rankedLists = List.copyOf(rankedLists);
            outcomes = List.copyOf(outcomes);
        }
    }

    /** 单个物理分支的安全执行事实；失败时 rankedList 为空。 */
    record BranchOutcome(
            RetrievalBranchRequest branch,
            RankedCandidateList rankedList,
            String status,
            Duration duration
    ) {
        BranchOutcome {
            Objects.requireNonNull(branch, "branch must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            duration = Objects.requireNonNull(duration, "duration must not be null");
        }

        boolean succeeded() {
            return rankedList != null;
        }
    }
}
