package dev.infinityknowledge.runtime;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.runtime.evidence.DefaultEvidenceBuilder;
import dev.infinityknowledge.runtime.fusion.ReciprocalRankFusion;
import dev.infinityknowledge.runtime.query.DefaultQueryAnalyzer;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.Retriever;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证检索运行时的融合、降级、租户边界和安全 Trace。
 */
class DefaultKnowledgeGatewayTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");

    /**
     * 验证同一 Chunk 被多通道命中后只产生一条证据并保留两个通道。
     */
    @Test
    void fusesChannelsAndBuildsCitation() {
        UUID chunkId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        DocumentId documentId = DocumentId.random();
        List<RetrievalTrace> traces = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gateway(
                List.of(
                        retriever(RetrievalChannel.KEYWORD, candidate(
                                chunkId, revisionId, documentId,
                                RetrievalChannel.KEYWORD, 1, 0.76D, TENANT
                        )),
                        retriever(RetrievalChannel.VECTOR, candidate(
                                chunkId, revisionId, documentId,
                                RetrievalChannel.VECTOR, 2, 0.88D, TENANT
                        ))
                ),
                traces
        );

        EvidenceBundle result = gateway.retrieve(query("订单服务超时怎么办"));

        assertEquals(1, result.evidences().size());
        assertEquals(
                Set.of(RetrievalChannel.KEYWORD, RetrievalChannel.VECTOR),
                result.evidences().getFirst().channels()
        );
        assertEquals(documentId, result.evidences().getFirst().citation().documentId());
        assertTrue(result.sufficient());
        assertEquals(1, traces.size());
        assertEquals(7, traces.getFirst().steps().size());
        assertTrue(traces.getFirst().steps().stream().anyMatch(
                step -> "ACTIVE_REVISION_GUARD".equals(step.name())
        ));
    }

    /**
     * 验证缺失图 Retriever 会降级但不会抹去其他通道结果。
     */
    @Test
    void degradesWhenOptionalGraphRetrieverIsMissing() {
        RetrievalCandidate keyword = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                1,
                0.80D,
                TENANT
        );
        DefaultKnowledgeGateway gateway = gateway(
                List.of(
                        retriever(RetrievalChannel.KEYWORD, keyword),
                        retriever(RetrievalChannel.VECTOR, keywordWithChannel(
                                keyword,
                                RetrievalChannel.VECTOR
                        ))
                ),
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query("订单服务依赖哪些组件"));

        assertFalse(result.evidences().isEmpty());
        assertTrue(result.warnings().contains("RETRIEVER_GRAPH_UNAVAILABLE"));
    }

    /**
     * 验证任何 Retriever 返回跨租户候选时整个请求立即失败。
     */
    @Test
    void rejectsCrossTenantCandidate() {
        RetrievalCandidate leaked = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                1,
                0.99D,
                new TenantId("tenant-b")
        );
        DefaultKnowledgeGateway gateway = gateway(
                List.of(
                        retriever(RetrievalChannel.KEYWORD, leaked),
                        retriever(RetrievalChannel.VECTOR, keywordWithChannel(
                                leaked,
                                RetrievalChannel.VECTOR
                        ))
                ),
                new ArrayList<>()
        );

        assertThrows(SecurityException.class, () -> gateway.retrieve(query("敏感知识")));
    }

    @Test
    void rejectsExplicitDenyBeforeInvokingRetrievers() {
        AtomicInteger calls = new AtomicInteger();
        Retriever retriever = new Retriever() {
            @Override
            public RetrievalChannel channel() {
                return RetrievalChannel.KEYWORD;
            }

            @Override
            public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        DefaultKnowledgeGateway gateway = gateway(
                (principal, requested) -> AccessScope.denyAll(principal.tenantId()),
                List.of(retriever),
                new ArrayList<>()
        );

        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> gateway.retrieve(query("没有授权的知识"))
        );
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsCandidateOutsideDocumentWhitelist() {
        RetrievalCandidate candidate = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                1,
                0.90D,
                TENANT
        );
        DefaultKnowledgeGateway gateway = gateway(
                (principal, requested) -> AccessScope.only(
                        principal.tenantId(),
                        Set.of(SPACE),
                        Set.of(DocumentId.random().value().toString())
                ),
                List.of(retriever(RetrievalChannel.KEYWORD, candidate)),
                new ArrayList<>()
        );

        assertThrows(
                SecurityException.class,
                () -> gateway.retrieve(query("受限文档"))
        );
    }

    @Test
    void rejectsPolicyScopeFromDifferentTenant() {
        DefaultKnowledgeGateway gateway = gateway(
                (principal, requested) -> AccessScope.all(
                        new TenantId("tenant-b"),
                        Set.of(SPACE)
                ),
                List.of(),
                new ArrayList<>()
        );

        assertThrows(
                SecurityException.class,
                () -> gateway.retrieve(query("租户边界"))
        );
    }

    @Test
    void removesCandidatesThatAreNoLongerTheActiveRevision() {
        DocumentId documentId = DocumentId.random();
        UUID activeRevisionId = UUID.randomUUID();
        RetrievalCandidate stale = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                documentId,
                RetrievalChannel.KEYWORD,
                1,
                0.99D,
                TENANT
        );
        RetrievalCandidate active = candidate(
                UUID.randomUUID(),
                activeRevisionId,
                documentId,
                RetrievalChannel.KEYWORD,
                2,
                0.80D,
                TENANT
        );
        ActiveRevisionGuard activeOnly = guardRetaining(activeRevisionId);
        DefaultKnowledgeGateway gateway = gateway(
                (principal, requested) -> AccessScope.all(principal.tenantId(), Set.of(SPACE)),
                activeOnly,
                List.of(retriever(RetrievalChannel.KEYWORD, List.of(stale, active))),
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query("活动修订"));

        assertEquals(1, result.evidences().size());
        assertEquals(activeRevisionId, result.evidences().getFirst().citation().revisionId());
    }

    /**
     * 创建使用同步执行器的确定性 Gateway。
     *
     * @param retrievers Retriever 集合
     * @param traces Trace 收集器
     * @return 测试 Gateway
     */
    private DefaultKnowledgeGateway gateway(
            List<Retriever> retrievers,
            List<RetrievalTrace> traces
    ) {
        AccessPolicy policy = (principal, requested) -> new AccessScope(
                principal.tenantId(),
                Set.of(SPACE),
                Set.of()
        );
        return gateway(policy, retrievers, traces);
    }

    private DefaultKnowledgeGateway gateway(
            AccessPolicy policy,
            List<Retriever> retrievers,
            List<RetrievalTrace> traces
    ) {
        return gateway(policy, allowAllRevisions(), retrievers, traces);
    }

    private DefaultKnowledgeGateway gateway(
            AccessPolicy policy,
            ActiveRevisionGuard activeRevisionGuard,
            List<Retriever> retrievers,
            List<RetrievalTrace> traces
    ) {
        return new DefaultKnowledgeGateway(
                policy,
                activeRevisionGuard,
                new DefaultQueryAnalyzer(5),
                retrievers,
                new ReciprocalRankFusion(60),
                new DefaultEvidenceBuilder(),
                traces::add,
                Runnable::run,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC),
                0.5D
        );
    }

    /**
     * 创建测试查询。
     *
     * @param text 查询文本
     * @return 知识查询
     */
    private KnowledgeQuery query(String text) {
        return new KnowledgeQuery(
                UUID.randomUUID(),
                new PrincipalContext(
                        TENANT,
                        new PrincipalId("user-1"),
                        Set.of("reader"),
                        Set.of("engineering"),
                        false
                ),
                text,
                Set.of(SPACE),
                5,
                Map.of()
        );
    }

    /**
     * 创建固定返回候选的 Retriever。
     *
     * @param channel 召回通道
     * @param candidate 固定候选
     * @return Retriever
     */
    private Retriever retriever(RetrievalChannel channel, RetrievalCandidate candidate) {
        return retriever(channel, List.of(candidate));
    }

    private Retriever retriever(
            RetrievalChannel channel,
            List<RetrievalCandidate> candidates
    ) {
        return new Retriever() {
            /**
             * 返回测试通道。
             *
             * @return 通道
             */
            @Override
            public RetrievalChannel channel() {
                return channel;
            }

            /**
             * 返回固定候选。
             *
             * @param request 检索请求
             * @return 固定候选
             */
            @Override
            public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
                return candidates;
            }
        };
    }

    private ActiveRevisionGuard allowAllRevisions() {
        return new ActiveRevisionGuard() {
            @Override
            public boolean isActive(TenantId tenantId, DocumentId documentId, UUID revisionId) {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<RetrievalCandidate> candidates
            ) {
                return List.copyOf(candidates);
            }
        };
    }

    private ActiveRevisionGuard guardRetaining(UUID activeRevisionId) {
        return new ActiveRevisionGuard() {
            @Override
            public boolean isActive(TenantId tenantId, DocumentId documentId, UUID revisionId) {
                return activeRevisionId.equals(revisionId);
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<RetrievalCandidate> candidates
            ) {
                return candidates.stream()
                        .filter(candidate -> activeRevisionId.equals(candidate.revisionId()))
                        .toList();
            }
        };
    }

    /**
     * 创建候选。
     *
     * @param chunkId Chunk 标识
     * @param revisionId 修订标识
     * @param documentId 文档标识
     * @param channel 通道
     * @param rank 排名
     * @param score 评分
     * @param tenant 租户
     * @return 候选
     */
    private RetrievalCandidate candidate(
            UUID chunkId,
            UUID revisionId,
            DocumentId documentId,
            RetrievalChannel channel,
            int rank,
            double score,
            TenantId tenant
    ) {
        return new RetrievalCandidate(
                chunkId,
                tenant,
                SPACE,
                documentId,
                revisionId,
                channel,
                rank,
                score,
                "订单服务故障手册",
                List.of("超时处理"),
                "检查连接池、下游依赖和最近配置变更。",
                "https://knowledge.example/order-timeout",
                Map.of("authority", "90")
        );
    }

    /**
     * 复制候选并替换召回通道。
     *
     * @param source 原候选
     * @param channel 新通道
     * @return 新候选
     */
    private RetrievalCandidate keywordWithChannel(
            RetrievalCandidate source,
            RetrievalChannel channel
    ) {
        return new RetrievalCandidate(
                source.chunkId(),
                source.tenantId(),
                source.spaceId(),
                source.documentId(),
                source.revisionId(),
                channel,
                source.rank(),
                source.score(),
                source.title(),
                source.sectionPath(),
                source.content(),
                source.sourceUri(),
                source.metadata()
        );
    }
}
