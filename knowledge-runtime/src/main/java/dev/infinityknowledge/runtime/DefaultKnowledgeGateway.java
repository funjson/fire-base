package dev.infinityknowledge.runtime;

import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.runtime.evidence.DefaultEvidenceBuilder;
import dev.infinityknowledge.runtime.fusion.FusedCandidate;
import dev.infinityknowledge.runtime.fusion.ReciprocalRankFusion;
import dev.infinityknowledge.runtime.support.Hashing;
import dev.infinityknowledge.spi.KnowledgeGateway;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.retrieval.QueryAnalyzer;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.Reranker;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.trace.TraceSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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

/**
 * 编排授权、查询分析、多路召回、活动修订校验、融合、证据构建和安全 Trace。
 */
public final class DefaultKnowledgeGateway implements KnowledgeGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            DefaultKnowledgeGateway.class
    );
    private final AccessPolicy accessPolicy;
    private final ActiveRevisionGuard activeRevisionGuard;
    private final QueryAnalyzer queryAnalyzer;
    private final Map<RetrievalChannel, Retriever> retrievers;
    private final ReciprocalRankFusion fusion;
    private final Reranker reranker;
    private final DefaultEvidenceBuilder evidenceBuilder;
    private final TraceSink traceSink;
    private final Executor executor;
    private final Clock clock;
    private final double sufficientThreshold;
    private final Duration requestTimeout;
    private final Duration channelTimeout;

    /**
     * 创建知识运行时门面，并校验每个通道只有一个 Retriever。
     *
     * @param accessPolicy 授权策略
     * @param activeRevisionGuard 活动修订校验器
     * @param queryAnalyzer 查询分析器
     * @param retrievers 召回实现
     * @param fusion 排名融合器
     * @param evidenceBuilder 证据构建器
     * @param traceSink Trace 持久化端口
     * @param executor 多路召回执行器
     * @param clock 统一时钟
     * @param sufficientThreshold 证据充分性阈值
     */
    public DefaultKnowledgeGateway(
            AccessPolicy accessPolicy,
            ActiveRevisionGuard activeRevisionGuard,
            QueryAnalyzer queryAnalyzer,
            List<Retriever> retrievers,
            ReciprocalRankFusion fusion,
            DefaultEvidenceBuilder evidenceBuilder,
            TraceSink traceSink,
            Executor executor,
            Clock clock,
            double sufficientThreshold
    ) {
        this(
                accessPolicy,
                activeRevisionGuard,
                queryAnalyzer,
                retrievers,
                fusion,
                Reranker.passthrough(),
                evidenceBuilder,
                traceSink,
                executor,
                clock,
                sufficientThreshold,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30)
        );
    }

    /**
     * Creates a gateway with optional reranking and bounded retrieval execution.
     *
     * @param accessPolicy authorization policy
     * @param activeRevisionGuard active revision guard
     * @param queryAnalyzer query analyzer
     * @param retrievers retrieval channels
     * @param fusion rank fusion
     * @param reranker authorized candidate reranker
     * @param evidenceBuilder evidence builder
     * @param traceSink trace sink
     * @param executor retrieval executor
     * @param clock application clock
     * @param sufficientThreshold evidence sufficiency threshold
     * @param requestTimeout total request timeout
     * @param channelTimeout timeout for each retrieval channel
     */
    public DefaultKnowledgeGateway(
            AccessPolicy accessPolicy,
            ActiveRevisionGuard activeRevisionGuard,
            QueryAnalyzer queryAnalyzer,
            List<Retriever> retrievers,
            ReciprocalRankFusion fusion,
            Reranker reranker,
            DefaultEvidenceBuilder evidenceBuilder,
            TraceSink traceSink,
            Executor executor,
            Clock clock,
            double sufficientThreshold,
            Duration requestTimeout,
            Duration channelTimeout
    ) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
        this.queryAnalyzer = Objects.requireNonNull(queryAnalyzer, "queryAnalyzer must not be null");
        this.retrievers = indexRetrievers(retrievers);
        this.fusion = Objects.requireNonNull(fusion, "fusion must not be null");
        this.reranker = Objects.requireNonNull(reranker, "reranker must not be null");
        this.evidenceBuilder = Objects.requireNonNull(
                evidenceBuilder,
                "evidenceBuilder must not be null"
        );
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (!Double.isFinite(sufficientThreshold)
                || sufficientThreshold < 0.0D
                || sufficientThreshold > 1.0D) {
            throw new IllegalArgumentException(
                    "sufficientThreshold must be finite and between 0 and 1"
            );
        }
        this.sufficientThreshold = sufficientThreshold;
        this.requestTimeout = requirePositiveTimeout(requestTimeout, "requestTimeout");
        this.channelTimeout = requirePositiveTimeout(channelTimeout, "channelTimeout");
        if (this.channelTimeout.compareTo(this.requestTimeout) > 0) {
            throw new IllegalArgumentException(
                    "channelTimeout must not be greater than requestTimeout"
            );
        }
    }

    /**
     * 执行完整检索流程；单通道失败会降级并产生警告，跨租户候选会立即拒绝。
     *
     * @param query 已绑定认证主体的知识查询
     * @return 可追溯证据包
     */
    @Override
    public EvidenceBundle retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(requestTimeout);
        long requestDeadlineNanos = System.nanoTime() + requestTimeout.toNanos();
        UUID traceId = UUID.randomUUID();
        List<RetrievalStepTrace> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        AccessScope scope = measure(
                "ACCESS_POLICY",
                1,
                steps,
                () -> accessPolicy.resolve(query.principal(), query.spaceIds())
        );
        validateAccessScope(query, scope);
        QueryPlan plan = measure(
                "QUERY_ANALYSIS",
                1,
                steps,
                () -> queryAnalyzer.analyze(query)
        );
        RetrievalRequest request = new RetrievalRequest(query, plan, scope, deadline);
        List<RetrievalCandidate> candidates = retrieveAll(
                request,
                plan,
                steps,
                warnings,
                requestDeadlineNanos
        );
        ensureAuthorizedCandidates(scope, candidates);
        List<RetrievalCandidate> activeCandidates = measure(
                "ACTIVE_REVISION_GUARD",
                candidates.size(),
                steps,
                () -> List.copyOf(Objects.requireNonNull(
                        activeRevisionGuard.retainActive(
                                query.principal().tenantId(),
                                candidates
                        ),
                        "activeRevisionGuard must not return null"
                ))
        );
        ensureAuthorizedCandidates(scope, activeCandidates);
        List<FusedCandidate> fusedPool = measure(
                "RRF_FUSION",
                activeCandidates.size(),
                steps,
                () -> fusion.fuse(activeCandidates, plan.candidateLimit())
        );
        List<FusedCandidate> reranked = rerankWithFallback(
                query,
                fusedPool,
                scope,
                steps,
                warnings,
                requestDeadlineNanos
        );
        List<Evidence> evidence = measure(
                "EVIDENCE_BUILD",
                reranked.size(),
                steps,
                () -> evidenceBuilder.build(reranked)
        );
        boolean sufficient = !evidence.isEmpty()
                && evidence.getFirst().relevance() >= sufficientThreshold;
        Instant completedAt = clock.instant();
        RetrievalTrace trace = new RetrievalTrace(
                traceId,
                query.requestId(),
                query.principal().tenantId(),
                query.principal().principalId(),
                Hashing.sha256(query.text()),
                steps,
                nonNegativeDuration(startedAt, completedAt),
                evidence.size(),
                startedAt
        );
        try {
            traceSink.append(trace);
        } catch (RuntimeException traceFailure) {
            warnings.add("TRACE_PERSISTENCE_FAILED");
            LOGGER.error(
                    "Trace persistence failed: traceId={}, requestId={}, tenantId={}, "
                            + "failureType={}",
                    traceId,
                    query.requestId(),
                    query.principal().tenantId().value(),
                    traceFailure.getClass().getSimpleName()
            );
        }
        return new EvidenceBundle(
                query.requestId(),
                traceId,
                query.principal().tenantId(),
                evidence,
                sufficient,
                warnings,
                completedAt
        );
    }

    /**
     * 并行执行计划中的 Retriever，并把失败通道转换为稳定降级警告。
     *
     * @param request 检索请求
     * @param plan 检索计划
     * @param steps Trace 阶段集合
     * @param warnings 降级警告集合
     * @return 全部成功通道的候选
     */
    private List<RetrievalCandidate> retrieveAll(
            RetrievalRequest request,
            QueryPlan plan,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos
    ) {
        List<ChannelTask> tasks = plan.channels().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(channel -> submitChannel(channel, request))
                .toList();
        List<RetrievalCandidate> candidates = new ArrayList<>();
        try {
            for (ChannelTask task : tasks) {
                ChannelResult result = awaitChannel(task, requestDeadlineNanos);
                steps.add(result.trace());
                if (result.failure()) {
                    addFailureWarning(warnings, result);
                } else {
                    candidates.addAll(result.candidates());
                }
            }
        } catch (InterruptedException interrupted) {
            cancelUnfinished(tasks);
            Thread.currentThread().interrupt();
            CancellationException cancellation = new CancellationException(
                    "retrieval request interrupted"
            );
            cancellation.initCause(interrupted);
            throw cancellation;
        }
        return List.copyOf(candidates);
    }

    /**
     * Submits one retriever through a cancellable FutureTask. FutureTask is used instead of
     * CompletableFuture so cancellation can interrupt blocking adapter calls.
     */
    private ChannelTask submitChannel(
            RetrievalChannel channel,
            RetrievalRequest request
    ) {
        Instant startedAt = clock.instant();
        long deadlineNanos = System.nanoTime() + channelTimeout.toNanos();
        FutureTask<ChannelResult> future = new FutureTask<>(
                () -> retrieveChannel(channel, request)
        );
        try {
            executor.execute(future);
        } catch (RejectedExecutionException rejected) {
            LOGGER.warn(
                    "Retriever execution rejected: channel={}, requestId={}, tenantId={}",
                    channel.name(),
                    request.query().requestId(),
                    request.query().principal().tenantId().value()
            );
            future = new FutureTask<>(() -> ChannelResult.failed(
                    channel,
                    nonNegativeDuration(startedAt, clock.instant()),
                    "REJECTED"
            ));
            future.run();
        }
        return new ChannelTask(channel, future, startedAt, deadlineNanos);
    }

    /**
     * Waits no longer than the earliest channel or request deadline.
     */
    private ChannelResult awaitChannel(
            ChannelTask task,
            long requestDeadlineNanos
    ) throws InterruptedException {
        long effectiveDeadline = Math.min(requestDeadlineNanos, task.deadlineNanos());
        long remaining = effectiveDeadline - System.nanoTime();
        if (remaining <= 0L && !task.future().isDone()) {
            task.future().cancel(true);
            return timeoutResult(task, requestDeadlineNanos <= task.deadlineNanos());
        }
        try {
            return task.future().get(Math.max(0L, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            task.future().cancel(true);
            return timeoutResult(
                    task,
                    requestDeadlineNanos <= task.deadlineNanos()
            );
        } catch (ExecutionException executionFailure) {
            LOGGER.warn(
                    "Retriever task failed unexpectedly: channel={}",
                    task.channel().name(),
                    executionFailure.getCause()
            );
            return ChannelResult.failed(
                    task.channel(),
                    nonNegativeDuration(task.startedAt(), clock.instant()),
                    "FAILED"
            );
        } catch (CancellationException cancelled) {
            return ChannelResult.failed(
                    task.channel(),
                    nonNegativeDuration(task.startedAt(), clock.instant()),
                    "CANCELLED"
            );
        }
    }

    private ChannelResult timeoutResult(ChannelTask task, boolean requestDeadline) {
        return ChannelResult.failed(
                task.channel(),
                nonNegativeDuration(task.startedAt(), clock.instant()),
                requestDeadline ? "DEADLINE_EXCEEDED" : "TIMEOUT"
        );
    }

    private void cancelUnfinished(List<ChannelTask> tasks) {
        tasks.stream()
                .map(ChannelTask::future)
                .filter(future -> !future.isDone())
                .forEach(future -> future.cancel(true));
    }

    private void addFailureWarning(List<String> warnings, ChannelResult result) {
        String channelPrefix = "RETRIEVER_" + result.channel().name();
        switch (result.trace().status()) {
            case "DEADLINE_EXCEEDED" -> {
                addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
                addWarning(warnings, channelPrefix + "_TIMEOUT");
            }
            case "TIMEOUT" -> addWarning(warnings, channelPrefix + "_TIMEOUT");
            case "REJECTED" -> addWarning(warnings, channelPrefix + "_OVERLOADED");
            default -> addWarning(warnings, channelPrefix + "_UNAVAILABLE");
        }
    }

    private void addWarning(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    /**
     * Applies reranking only after ACL and active-revision filtering, and rejects injected data.
     */
    private List<FusedCandidate> rerankWithFallback(
            KnowledgeQuery query,
            List<FusedCandidate> candidates,
            AccessScope scope,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            long requestDeadlineNanos
    ) {
        Instant startedAt = clock.instant();
        FutureTask<List<FusedCandidate>> task = new FutureTask<>(() -> rerank(
                query.text(),
                candidates,
                query.topK(),
                scope
        ));
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            addWarning(warnings, "RERANKER_OVERLOADED");
            return rerankFallback(query, candidates, steps, warnings, startedAt, rejected);
        }
        long remaining = requestDeadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            task.cancel(true);
            addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            addWarning(warnings, "RERANKER_TIMEOUT");
            return rerankFallback(
                    query, candidates, steps, warnings, startedAt,
                    new TimeoutException("request deadline elapsed before reranking")
            );
        }
        try {
            List<FusedCandidate> result = task.get(remaining, TimeUnit.NANOSECONDS);
            steps.add(new RetrievalStepTrace(
                    "RERANK",
                    nonNegativeDuration(startedAt, clock.instant()),
                    candidates.size(),
                    result.size(),
                    "SUCCEEDED"
            ));
            return result;
        } catch (TimeoutException timeout) {
            task.cancel(true);
            addWarning(warnings, "RETRIEVAL_DEADLINE_EXCEEDED");
            addWarning(warnings, "RERANKER_TIMEOUT");
            return rerankFallback(query, candidates, steps, warnings, startedAt, timeout);
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            CancellationException cancellation = new CancellationException(
                    "retrieval request interrupted during reranking"
            );
            cancellation.initCause(interrupted);
            throw cancellation;
        } catch (ExecutionException executionFailure) {
            return rerankFallback(
                    query,
                    candidates,
                    steps,
                    warnings,
                    startedAt,
                    executionFailure.getCause()
            );
        } catch (CancellationException cancelled) {
            return rerankFallback(query, candidates, steps, warnings, startedAt, cancelled);
        }
    }

    private List<FusedCandidate> rerankFallback(
            KnowledgeQuery query,
            List<FusedCandidate> candidates,
            List<RetrievalStepTrace> steps,
            List<String> warnings,
            Instant startedAt,
            Throwable failure
    ) {
        List<FusedCandidate> fallback = candidates.stream()
                .limit(query.topK())
                .toList();
        addWarning(warnings, "RERANKER_UNAVAILABLE");
        steps.add(new RetrievalStepTrace(
                "RERANK",
                nonNegativeDuration(startedAt, clock.instant()),
                candidates.size(),
                fallback.size(),
                "DEGRADED"
        ));
        LOGGER.warn(
                "Reranker unavailable; using fused order: requestId={}, tenantId={}, "
                        + "failureType={}",
                query.requestId(),
                query.principal().tenantId().value(),
                failure == null ? "Unknown" : failure.getClass().getSimpleName()
        );
        return fallback;
    }

    /**
     * Applies a configured reranker and validates that it only reorders the supplied candidates.
     */
    private List<FusedCandidate> rerank(
            String query,
            List<FusedCandidate> candidates,
            int limit,
            AccessScope scope
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievalCandidate> representatives = candidates.stream()
                .map(FusedCandidate::representative)
                .toList();
        int boundedLimit = Math.min(limit, representatives.size());
        List<RetrievalCandidate> reranked = List.copyOf(Objects.requireNonNull(
                reranker.rerank(query, representatives, boundedLimit),
                "reranker must not return null"
        ));
        Set<RetrievalCandidate> allowed = new HashSet<>(representatives);
        Set<RetrievalCandidate> unique = new HashSet<>(reranked);
        if (reranked.size() > boundedLimit
                || unique.size() != reranked.size()
                || !allowed.containsAll(reranked)) {
            throw new SecurityException("reranker returned unknown or duplicate candidates");
        }
        ensureAuthorizedCandidates(scope, reranked);
        Map<UUID, FusedCandidate> byChunk = candidates.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value.representative().chunkId(),
                        value -> value
                )
        );
        return reranked.stream()
                .map(value -> byChunk.get(value.chunkId()))
                .toList();
    }

    /**
     * 执行单个召回通道并隔离基础设施异常。
     *
     * @param channel 召回通道
     * @param request 检索请求
     * @return 通道结果和 Trace
     */
    private ChannelResult retrieveChannel(
            RetrievalChannel channel,
            RetrievalRequest request
    ) {
        Instant startedAt = clock.instant();
        Retriever retriever = retrievers.get(channel);
        if (retriever == null) {
            return ChannelResult.failed(
                    channel,
                    nonNegativeDuration(startedAt, clock.instant()),
                    "NOT_CONFIGURED"
            );
        }
        try {
            List<RetrievalCandidate> result = List.copyOf(retriever.retrieve(request));
            return ChannelResult.succeeded(
                    channel,
                    result,
                    nonNegativeDuration(startedAt, clock.instant())
            );
        } catch (RuntimeException retrievalFailure) {
            LOGGER.warn(
                    "Retriever failed: channel={}, requestId={}, tenantId={}",
                    channel.name(),
                    request.query().requestId(),
                    request.query().principal().tenantId().value(),
                    retrievalFailure
            );
            return ChannelResult.failed(
                    channel,
                    nonNegativeDuration(startedAt, clock.instant()),
                    "FAILED"
            );
        }
    }

    /**
     * 验证基础设施返回值仍处于 Policy Engine 生成的租户和空间范围。
     *
     * @param scope 授权范围
     * @param candidates 候选列表
     */
    private void ensureAuthorizedCandidates(
            AccessScope scope,
            List<RetrievalCandidate> candidates
    ) {
        for (RetrievalCandidate candidate : candidates) {
            if (!scope.tenantId().equals(candidate.tenantId())
                    || !scope.allowsSpace(candidate.spaceId())) {
                throw new SecurityException(
                        "retriever returned candidate outside authorized scope"
                );
            }
            if (!scope.allowsDocument(candidate.documentId().value().toString())) {
                throw new SecurityException(
                        "retriever returned document outside authorized scope"
                );
            }
        }
    }

    /**
     * 在查询分析或任何 Retriever 执行前校验策略结果。
     *
     * <p>Policy 只能收窄调用方请求，不能改变租户或扩展到未请求的空间。</p>
     */
    private void validateAccessScope(KnowledgeQuery query, AccessScope scope) {
        Objects.requireNonNull(scope, "accessPolicy must not return null");
        if (!query.principal().tenantId().equals(scope.tenantId())) {
            throw new SecurityException("access policy returned a different tenant");
        }
        if (scope.deniesAll()) {
            throw new KnowledgeAccessDeniedException("No readable knowledge space");
        }
        if (!query.spaceIds().isEmpty()
                && !query.spaceIds().containsAll(scope.spaceIds())) {
            throw new SecurityException("access policy expanded the requested spaces");
        }
    }

    /**
     * 建立通道到 Retriever 的唯一映射。
     *
     * @param values Retriever 列表
     * @return 不可变通道映射
     */
    private Map<RetrievalChannel, Retriever> indexRetrievers(List<Retriever> values) {
        Objects.requireNonNull(values, "retrievers must not be null");
        EnumMap<RetrievalChannel, Retriever> indexed = new EnumMap<>(RetrievalChannel.class);
        for (Retriever retriever : values) {
            Objects.requireNonNull(retriever, "retriever must not be null");
            Retriever previous = indexed.putIfAbsent(retriever.channel(), retriever);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate retriever channel " + retriever.channel()
                );
            }
        }
        return Map.copyOf(indexed);
    }

    /**
     * 记录同步阶段耗时和输出数量。
     *
     * @param name 阶段名称
     * @param inputCount 输入数量
     * @param steps Trace 集合
     * @param action 阶段动作
     * @param <T> 结果类型
     * @return 阶段结果
     */
    private <T> T measure(
            String name,
            int inputCount,
            List<RetrievalStepTrace> steps,
            java.util.function.Supplier<T> action
    ) {
        Instant startedAt = clock.instant();
        T result = action.get();
        int outputCount = result instanceof java.util.Collection<?> collection
                ? collection.size()
                : 1;
        steps.add(new RetrievalStepTrace(
                name,
                nonNegativeDuration(startedAt, clock.instant()),
                inputCount,
                outputCount,
                "SUCCEEDED"
        ));
        return result;
    }

    /**
     * 防止测试时钟或外部时钟回拨产生非法负耗时。
     *
     * @param start 开始时间
     * @param end 结束时间
     * @return 非负耗时
     */
    private Duration nonNegativeDuration(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private Duration requirePositiveTimeout(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name + " must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(name + " must be between 1ns and 10 minutes");
        }
        return timeout;
    }

    private record ChannelTask(
            RetrievalChannel channel,
            FutureTask<ChannelResult> future,
            Instant startedAt,
            long deadlineNanos
    ) {
    }

    /**
     * 保存一个通道的候选和安全 Trace 摘要。
     *
     * @param channel 召回通道
     * @param candidates 候选
     * @param trace 阶段 Trace
     * @param failure 是否失败
     */
    private record ChannelResult(
            RetrievalChannel channel,
            List<RetrievalCandidate> candidates,
            RetrievalStepTrace trace,
            boolean failure
    ) {

        /**
         * 创建成功通道结果。
         *
         * @param channel 通道
         * @param candidates 候选
         * @param duration 耗时
         * @return 成功结果
         */
        private static ChannelResult succeeded(
                RetrievalChannel channel,
                List<RetrievalCandidate> candidates,
                Duration duration
        ) {
            return new ChannelResult(
                    channel,
                    List.copyOf(candidates),
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

        /**
         * 创建失败或未配置的通道结果。
         *
         * @param channel 通道
         * @param duration 耗时
         * @param status 稳定状态
         * @return 失败结果
         */
        private static ChannelResult failed(
                RetrievalChannel channel,
                Duration duration,
                String status
        ) {
            return new ChannelResult(
                    channel,
                    List.of(),
                    new RetrievalStepTrace(
                            "RETRIEVER_" + channel.name(),
                            duration,
                            1,
                            0,
                            status
                    ),
                    true
            );
        }
    }
}
