package dev.infinityknowledge.runtime;

import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.runtime.evidence.DefaultEvidenceBuilder;
import dev.infinityknowledge.runtime.fusion.ReciprocalRankFusion;
import dev.infinityknowledge.runtime.support.Hashing;
import dev.infinityknowledge.spi.KnowledgeGateway;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.retrieval.QueryAnalyzer;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
    private final DefaultEvidenceBuilder evidenceBuilder;
    private final TraceSink traceSink;
    private final Executor executor;
    private final Clock clock;
    private final double sufficientThreshold;

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
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
        this.queryAnalyzer = Objects.requireNonNull(queryAnalyzer, "queryAnalyzer must not be null");
        this.retrievers = indexRetrievers(retrievers);
        this.fusion = Objects.requireNonNull(fusion, "fusion must not be null");
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
        RetrievalRequest request = new RetrievalRequest(query, plan, scope);
        List<RetrievalCandidate> candidates = retrieveAll(request, plan, steps, warnings);
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
        var fused = measure(
                "RRF_FUSION",
                activeCandidates.size(),
                steps,
                () -> fusion.fuse(activeCandidates, query.topK())
        );
        List<Evidence> evidence = measure(
                "EVIDENCE_BUILD",
                fused.size(),
                steps,
                () -> evidenceBuilder.build(fused)
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
                    "Trace persistence failed: traceId={}, requestId={}, tenantId={}",
                    traceId,
                    query.requestId(),
                    query.principal().tenantId().value(),
                    traceFailure
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
            List<String> warnings
    ) {
        List<CompletableFuture<ChannelResult>> futures = plan.channels().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> retrieveChannel(channel, request),
                        executor
                ))
                .toList();
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (CompletableFuture<ChannelResult> future : futures) {
            ChannelResult result = future.join();
            steps.add(result.trace());
            if (result.failure()) {
                warnings.add("RETRIEVER_" + result.channel().name() + "_UNAVAILABLE");
            } else {
                candidates.addAll(result.candidates());
            }
        }
        return List.copyOf(candidates);
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
