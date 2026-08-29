package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationOverride;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.retrieval.evidence.DefaultEvidenceBuilder;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.FeedbackQueryPlannerComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.RerankerComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.TerminologyServiceComponent;
import dev.infinityknowledge.retrieval.fusion.ReciprocalRankFusion;
import dev.infinityknowledge.retrieval.observation.HmacSha256TextFingerprinter;
import dev.infinityknowledge.retrieval.query.DefaultQueryAnalyzer;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ActiveIndexGeneration;
import dev.infinityknowledge.spi.indexing.ActiveIndexGenerationCatalog;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import dev.infinityknowledge.spi.retrieval.RerankResult;
import dev.infinityknowledge.spi.retrieval.Reranker;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.RetrievalComponentVersion;
import dev.infinityknowledge.spi.retrieval.RetrievalSpaceCatalog;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingCandidate;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansion;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionRequest;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionResult;
import dev.infinityknowledge.spi.retrieval.TerminologyService;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        assertFalse(result.sufficient());
        assertEquals(RetrievalTerminalStatus.NOT_EVALUATED, result.terminalStatus());
        assertEquals(1, traces.size());
        assertTrue(traces.getFirst().steps().stream()
                .map(step -> step.name())
                .toList()
                .containsAll(List.of(
                        "ACCESS_POLICY",
                        "SPACE_ROUTING",
                        "CONFIGURATION_RESOLVED",
                        "QUERY_ANALYSIS",
                        "QUERY_PLANNING",
                        "RRF_FUSION",
                        "RERANK",
                        "EVIDENCE_BUILD"
                )));
        int fusionStep = stepIndex(traces.getFirst(), "RRF_FUSION");
        int rerankStep = stepIndex(traces.getFirst(), "RERANK");
        assertTrue(fusionStep < rerankStep);
        assertTrue(traces.getFirst().steps().stream().anyMatch(
                step -> "RERANK".equals(step.name())
        ));
    }

    private static int stepIndex(RetrievalTrace trace, String name) {
        for (int index = 0; index < trace.steps().size(); index++) {
            if (name.equals(trace.steps().get(index).name())) {
                return index;
            }
        }
        return -1;
    }

    @Test
    void fusesAllChannelsBeforeApplyingRerankCandidateLimit() {
        UUID sharedChunk = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        DocumentId documentId = DocumentId.random();
        List<RetrievalCandidate> keyword = new ArrayList<>();
        for (int rank = 1; rank <= 4; rank++) {
            keyword.add(candidate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    DocumentId.random(),
                    RetrievalChannel.KEYWORD,
                    rank,
                    0.80D,
                    TENANT
            ));
        }
        keyword.add(candidate(
                sharedChunk,
                revisionId,
                documentId,
                RetrievalChannel.KEYWORD,
                5,
                0.90D,
                TENANT
        ));
        RetrievalCandidate vector = candidate(
                sharedChunk,
                revisionId,
                documentId,
                RetrievalChannel.VECTOR,
                1,
                0.90D,
                TENANT
        );
        DefaultKnowledgeGateway gateway = gateway(
                List.of(
                        retriever(RetrievalChannel.KEYWORD, keyword),
                        retriever(RetrievalChannel.VECTOR, vector)
                ),
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query("shared evidence", 1));

        assertEquals(1, result.evidences().size());
        assertEquals(sharedChunk, result.evidences().getFirst().citation().chunkId());
        assertEquals(
                Set.of(RetrievalChannel.KEYWORD, RetrievalChannel.VECTOR),
                result.evidences().getFirst().channels()
        );
    }

    @Test
    void expandsFromConfiguredTerminologyResourceAndAssignsComplementaryChannels() {
        AtomicReference<TerminologyExpansionRequest> observedRequest =
                new AtomicReference<>();
        TerminologyService terminologyService = request -> {
            observedRequest.set(request);
            return new TerminologyExpansionResult(
                    java.util.Optional.of(new TerminologyExpansion(
                            "订单服务 order-service 为什么登录失败",
                            List.of("order-service")
                    )),
                    "test",
                    "test-terminology-v1"
            );
        };
        List<RetrievalRequest> keywordRequests = new ArrayList<>();
        List<RetrievalRequest> vectorRequests = new ArrayList<>();
        RetrievalCandidate rewriteHit = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                1,
                0.80D,
                TENANT
        );
        Retriever keyword = new Retriever() {
            @Override
            public RetrievalComponentVersion componentVersion() {
                return retrieverVersion(RetrievalChannel.KEYWORD);
            }

            @Override
            public RetrievalChannel channel() {
                return RetrievalChannel.KEYWORD;
            }

            @Override
            public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
                keywordRequests.add(request);
                return "订单服务 order-service 为什么登录失败"
                        .equals(request.plan().normalizedQuery())
                        ? List.of(rewriteHit) : List.of();
            }
        };
        Retriever vector = capturingRetriever(RetrievalChannel.VECTOR, vectorRequests);
        List<RetrievalTrace> traces = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gatewayWithTerminologyService(
                List.of(keyword, vector),
                terminologyService,
                1,
                Runnable::run,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                traces
        );
        KnowledgeQuery query = new KnowledgeQuery(
                UUID.randomUUID(),
                new PrincipalContext(
                        TENANT,
                        new PrincipalId("user-1"),
                        Set.of("reader"),
                        Set.of("engineering"),
                        false
                ),
                "订单服务为什么登录失败？",
                Set.of(SPACE),
                2,
                Map.of("language", "zh"),
                dev.infinityknowledge.domain.retrieval.RetrievalConstraintInput.empty(),
                "定位登录失败原因",
                List.of(),
                RetrievalConfigurationOverride.empty(),
                RetrievalObservationPurpose.ONLINE
        );

        EvidenceBundle result = gateway.retrieve(query);

        assertEquals("test-terminology", observedRequest.get().terminologyResourceId());
        assertEquals(16, observedRequest.get().maximumExpansionTerms());
        assertEquals(1, keywordRequests.size());
        assertEquals(1, vectorRequests.size());
        assertEquals(
                "订单服务 order-service 为什么登录失败",
                keywordRequests.getFirst().plan().normalizedQuery()
        );
        assertEquals(
                "订单服务为什么登录失败？",
                vectorRequests.getFirst().plan().normalizedQuery()
        );
        assertTrue(java.util.stream.Stream.concat(
                keywordRequests.stream(),
                vectorRequests.stream()
        ).allMatch(request ->
                request.query().principal().equals(query.principal())
                        && request.query().spaceIds().equals(query.spaceIds())
                        && request.query().filters().equals(query.filters())
        ));
        assertEquals(1, result.evidences().size());
        assertEquals(rewriteHit.chunkId(), result.evidences().getFirst().citation().chunkId());
        assertTrue(traces.getFirst().steps().stream().anyMatch(step ->
                "QUERY_PLANNING".equals(step.name())
                        && step.outputCount() == 2
                        && "SUCCEEDED".equals(step.status())
        ));
    }

    @Test
    void fallsBackToOriginalQueryWhenTerminologyServiceFails() {
        List<RetrievalRequest> captured = new ArrayList<>();
        List<RetrievalTrace> traces = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gatewayWithTerminologyService(
                List.of(capturingRetriever(RetrievalChannel.KEYWORD, captured)),
                request -> {
                    throw new IllegalStateException("planner failed");
                },
                2,
                Runnable::run,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                traces
        );

        EvidenceBundle result = gateway.retrieve(query("保留 ERR-1001 原查询"));

        assertEquals(1, captured.size());
        assertEquals("保留 ERR-1001 原查询", captured.getFirst().plan().normalizedQuery());
        assertTrue(result.warnings().contains("TERMINOLOGY_SERVICE_UNAVAILABLE"));
        assertTrue(traces.getFirst().steps().stream().anyMatch(step ->
                "QUERY_PLANNING".equals(step.name())
                        && "DEGRADED".equals(step.status())
        ));
    }

    @Test
    void enforcesTerminologyStageTimeoutAndContinuesWithOriginalQuery()
            throws InterruptedException {
        CountDownLatch plannerInterrupted = new CountDownLatch(1);
        TerminologyService blocking = request -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                plannerInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return TerminologyExpansionResult.none("test", "v1");
        };
        List<RetrievalRequest> captured = new CopyOnWriteArrayList<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            DefaultKnowledgeGateway gateway = gatewayWithTerminologyService(
                    List.of(capturingRetriever(RetrievalChannel.KEYWORD, captured)),
                    blocking,
                    2,
                    executor,
                    Duration.ofSeconds(1),
                    Duration.ofMillis(20),
                    Duration.ofMillis(500),
                    new ArrayList<>()
            );

            EvidenceBundle result = gateway.retrieve(query("超时后仍检索原查询"));

            assertEquals(1, captured.size());
            assertEquals("超时后仍检索原查询", captured.getFirst().plan().normalizedQuery());
            assertTrue(result.warnings().contains("TERMINOLOGY_SERVICE_TIMEOUT"));
            assertTrue(result.warnings().contains("TERMINOLOGY_SERVICE_UNAVAILABLE"));
            assertFalse(result.warnings().contains("RETRIEVAL_DEADLINE_EXCEEDED"));
            assertTrue(plannerInterrupted.await(1, TimeUnit.SECONDS));
        }
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
            public RetrievalComponentVersion componentVersion() {
                return retrieverVersion(RetrievalChannel.KEYWORD);
            }

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

    @Test
    void reranksOnlyAuthorizedActiveCandidates() {
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
        AtomicInteger observedCandidates = new AtomicInteger();
        Reranker reranker = (text, candidates, limit) -> {
            observedCandidates.set(candidates.size());
            RetrievalCandidate selected = candidates.getFirst();
            return new RerankResult(
                    List.of(selected),
                    Map.of(selected.chunkId(), 0.20D),
                    1,
                    "TEST_MODEL"
            );
        };
        DefaultKnowledgeGateway gateway = gateway(
                (principal, requested) -> AccessScope.all(
                        principal.tenantId(),
                        Set.of(SPACE)
                ),
                guardRetaining(activeRevisionId),
                List.of(retriever(RetrievalChannel.KEYWORD, List.of(stale, active))),
                reranker,
                Runnable::run,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query("active revision reranking"));

        assertEquals(1, observedCandidates.get());
        assertEquals(activeRevisionId, result.evidences().getFirst().citation().revisionId());
        assertEquals(1.0D, result.evidences().getFirst().relevance());
        assertFalse(result.sufficient());
        assertEquals(RetrievalTerminalStatus.NOT_EVALUATED, result.terminalStatus());
    }

    @Test
    void fallsBackToWholeFusedOrderWhenRerankerScoresOnlyPartOfOutputWindow() {
        RetrievalCandidate first = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                1,
                0.90D,
                TENANT
        );
        RetrievalCandidate second = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                2,
                0.70D,
                TENANT
        );
        Reranker partial = (text, candidates, limit) -> new RerankResult(
                candidates.stream().limit(limit).toList(),
                Map.of(candidates.getFirst().chunkId(), 0.65D),
                1,
                "TEST_MODEL_PARTIAL"
        );
        List<RetrievalTrace> traces = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gateway(
                (principal, requested) -> AccessScope.all(
                        principal.tenantId(),
                        Set.of(SPACE)
                ),
                allowAllRevisions(),
                List.of(retriever(RetrievalChannel.KEYWORD, List.of(first, second))),
                partial,
                Runnable::run,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                traces
        );

        EvidenceBundle result = gateway.retrieve(query("partial model reranking", 2));

        assertEquals(2, result.evidences().size());
        assertEquals(1.0D, result.evidences().getFirst().relevance());
        assertEquals(
                61.0D / 62.0D,
                result.evidences().getLast().relevance(),
                1.0E-12D
        );
        assertFalse(result.sufficient());
        assertEquals(RetrievalTerminalStatus.NOT_EVALUATED, result.terminalStatus());
        assertTrue(result.warnings().contains("RERANKER_INCOMPLETE_OUTPUT"));
        assertTrue(result.warnings().contains("RERANKER_UNAVAILABLE"));
        assertTrue(traces.getFirst().steps().stream().anyMatch(
                step -> "RERANK".equals(step.name())
                        && "DEGRADED".equals(step.status())
        ));
    }

    @Test
    void degradesToFusedOrderWhenRerankerFails() {
        List<RetrievalTrace> traces = new ArrayList<>();
        RetrievalCandidate candidate = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                1,
                0.80D,
                TENANT
        );
        DefaultKnowledgeGateway gateway = gateway(
                (principal, requested) -> AccessScope.all(
                        principal.tenantId(),
                        Set.of(SPACE)
                ),
                allowAllRevisions(),
                List.of(retriever(RetrievalChannel.KEYWORD, candidate)),
                (text, candidates, limit) -> {
                    throw new IllegalStateException("provider failure");
                },
                Runnable::run,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                traces
        );

        EvidenceBundle result = gateway.retrieve(query("reranker fallback"));

        assertEquals(1, result.evidences().size());
        assertEquals(1.0D, result.evidences().getFirst().relevance());
        assertTrue(result.warnings().contains("RERANKER_UNAVAILABLE"));
        assertTrue(traces.getFirst().steps().stream().anyMatch(
                step -> "RERANK".equals(step.name())
                        && "DEGRADED".equals(step.status())
        ));
    }

    @Test
    void enforcesChannelTimeoutAndInterruptsRetriever() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        Retriever blocking = blockingRetriever(interrupted);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            DefaultKnowledgeGateway gateway = gateway(
                    (principal, requested) -> AccessScope.all(
                            principal.tenantId(),
                            Set.of(SPACE)
                    ),
                    allowAllRevisions(),
                    List.of(blocking),
                    Reranker.passthrough(),
                    executor,
                    Duration.ofSeconds(1),
                    Duration.ofMillis(20),
                    new ArrayList<>()
            );

            EvidenceBundle result = gateway.retrieve(query("bounded channel"));

            assertTrue(result.warnings().contains("RETRIEVER_KEYWORD_TIMEOUT"));
            assertFalse(result.warnings().contains("RETRIEVAL_DEADLINE_EXCEEDED"));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void enforcesRequestDeadlineAndExposesItToRetriever() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Instant> deadline = new AtomicReference<>();
        Retriever blocking = new Retriever() {
            @Override
            public RetrievalComponentVersion componentVersion() {
                return retrieverVersion(RetrievalChannel.KEYWORD);
            }

            @Override
            public RetrievalChannel channel() {
                return RetrievalChannel.KEYWORD;
            }

            @Override
            public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
                deadline.set(request.deadline());
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interruptedFailure) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            DefaultKnowledgeGateway gateway = gateway(
                    (principal, requested) -> AccessScope.all(
                            principal.tenantId(),
                            Set.of(SPACE)
                    ),
                    allowAllRevisions(),
                    List.of(blocking),
                    Reranker.passthrough(),
                    executor,
                    Duration.ofMillis(20),
                    Duration.ofMillis(20),
                    new ArrayList<>()
            );

            EvidenceBundle result = gateway.retrieve(query("bounded request"));

            assertTrue(result.warnings().contains("RETRIEVAL_DEADLINE_EXCEEDED"));
            assertEquals(
                    Instant.parse("2026-07-26T00:00:00.020Z"),
                    deadline.get()
            );
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void requestDeadlineAlsoBoundsRemoteReranking() throws InterruptedException {
        CountDownLatch rerankerInterrupted = new CountDownLatch(1);
        RetrievalCandidate candidate = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                1,
                0.8D,
                TENANT
        );
        Reranker blocking = (text, candidates, limit) -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                rerankerInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new RerankResult(
                    candidates.stream().limit(limit).toList(),
                    Map.of(),
                    0,
                    "PASSTHROUGH"
            );
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            DefaultKnowledgeGateway gateway = gateway(
                    (principal, requested) -> AccessScope.all(
                            principal.tenantId(),
                            Set.of(SPACE)
                    ),
                    allowAllRevisions(),
                    List.of(retriever(RetrievalChannel.KEYWORD, candidate)),
                    blocking,
                    executor,
                    Duration.ofMillis(30),
                    Duration.ofMillis(20),
                    new ArrayList<>()
            );

            EvidenceBundle result = gateway.retrieve(query("bounded reranker"));

            assertEquals(1, result.evidences().size());
            assertTrue(result.warnings().contains("RETRIEVAL_DEADLINE_EXCEEDED"));
            assertTrue(result.warnings().contains("RERANKER_TIMEOUT"));
            assertTrue(rerankerInterrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void rerankerStageTimeoutDoesNotConsumeTheWholeRequestDeadline()
            throws InterruptedException {
        CountDownLatch rerankerInterrupted = new CountDownLatch(1);
        RetrievalCandidate candidate = candidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentId.random(),
                RetrievalChannel.KEYWORD,
                1,
                0.8D,
                TENANT
        );
        Reranker blocking = (text, candidates, limit) -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                rerankerInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new RerankResult(
                    candidates.stream().limit(limit).toList(),
                    Map.of(),
                    0,
                    "PASSTHROUGH"
            );
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            DefaultKnowledgeGateway gateway = gateway(
                    (principal, requested) -> AccessScope.all(
                            principal.tenantId(),
                            Set.of(SPACE)
                    ),
                    allowAllRevisions(),
                    List.of(retriever(RetrievalChannel.KEYWORD, candidate)),
                    blocking,
                    executor,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofMillis(20),
                    new ArrayList<>()
            );

            EvidenceBundle result = gateway.retrieve(query("bounded reranker stage"));

            assertEquals(1, result.evidences().size());
            assertTrue(result.warnings().contains("RERANKER_TIMEOUT"));
            assertTrue(result.warnings().contains("RERANKER_UNAVAILABLE"));
            assertFalse(result.warnings().contains("RETRIEVAL_DEADLINE_EXCEEDED"));
            assertTrue(rerankerInterrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void degradesWhenRetrievalExecutorIsOverloaded() {
        Retriever keyword = retriever(
                RetrievalChannel.KEYWORD,
                candidate(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        DocumentId.random(),
                        RetrievalChannel.KEYWORD,
                        1,
                        0.8D,
                        TENANT
                )
        );
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        DefaultKnowledgeGateway gateway = gateway(
                (principal, requested) -> AccessScope.all(
                        principal.tenantId(),
                        Set.of(SPACE)
                ),
                allowAllRevisions(),
                List.of(keyword),
                Reranker.passthrough(),
                rejectingExecutor,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query("overloaded retrieval"));

        assertTrue(result.warnings().contains("RETRIEVER_KEYWORD_OVERLOADED"));
        assertTrue(result.evidences().isEmpty());
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
        return configuredGateway(
                policy,
                activeRevisionGuard,
                new DefaultQueryAnalyzer(5),
                emptyTerminologyService(),
                1,
                retrievers,
                Reranker.passthrough(),
                Runnable::run,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                traces
        );
    }

    /**
     * 创建启用首轮术语增强且使用透传精排的测试 Gateway。
     */
    private DefaultKnowledgeGateway gatewayWithTerminologyService(
            List<Retriever> retrievers,
            TerminologyService terminologyService,
            int maximumExpansionTerms,
            Executor executor,
            Duration requestTimeout,
            Duration queryPlannerTimeout,
            Duration channelTimeout,
            List<RetrievalTrace> traces
    ) {
        AccessPolicy policy = (principal, requested) -> AccessScope.all(
                principal.tenantId(),
                Set.of(SPACE)
        );
        return configuredGateway(
                policy,
                allowAllRevisions(),
                new DefaultQueryAnalyzer(
                        5,
                        retrievers.stream()
                                .map(Retriever::channel)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                ),
                terminologyService,
                maximumExpansionTerms,
                retrievers,
                Reranker.passthrough(),
                executor,
                requestTimeout,
                queryPlannerTimeout,
                channelTimeout,
                channelTimeout,
                traces
        );
    }

    private DefaultKnowledgeGateway gateway(
            AccessPolicy policy,
            ActiveRevisionGuard activeRevisionGuard,
            List<Retriever> retrievers,
            Reranker reranker,
            Executor executor,
            Duration requestTimeout,
            Duration channelTimeout,
            List<RetrievalTrace> traces
    ) {
        return gateway(
                policy,
                activeRevisionGuard,
                retrievers,
                reranker,
                executor,
                requestTimeout,
                channelTimeout,
                requestTimeout,
                traces
        );
    }

    private DefaultKnowledgeGateway gateway(
            AccessPolicy policy,
            ActiveRevisionGuard activeRevisionGuard,
            List<Retriever> retrievers,
            Reranker reranker,
            Executor executor,
            Duration requestTimeout,
            Duration channelTimeout,
            Duration rerankerTimeout,
            List<RetrievalTrace> traces
    ) {
        return configuredGateway(
                policy,
                activeRevisionGuard,
                new DefaultQueryAnalyzer(
                        5,
                        retrievers.stream()
                                .map(Retriever::channel)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                ),
                emptyTerminologyService(),
                1,
                retrievers,
                reranker,
                executor,
                requestTimeout,
                requestTimeout.compareTo(Duration.ofSeconds(5)) < 0
                        ? requestTimeout : Duration.ofSeconds(5),
                channelTimeout,
                rerankerTimeout,
                traces
        );
    }

    /**
     * 通过唯一生产构造器创建测试 Gateway；测试端口显式放在测试代码中，避免默认配置
     * 成为运行时的第二条业务链路。
     */
    private DefaultKnowledgeGateway configuredGateway(
            AccessPolicy policy,
            ActiveRevisionGuard activeRevisionGuard,
            DefaultQueryAnalyzer queryAnalyzer,
            TerminologyService terminologyService,
            int maximumExpansionTerms,
            List<Retriever> retrievers,
            Reranker reranker,
            Executor executor,
            Duration requestTimeout,
            Duration queryPlannerTimeout,
            Duration channelTimeout,
            Duration rerankerTimeout,
            List<RetrievalTrace> traces
    ) {
        RetrievalConfiguration configuration = testConfiguration(
                maximumExpansionTerms + 1
        );
        RetrievalSpaceCatalog catalog = (tenantId, allowedSpaceIds) ->
                allowedSpaceIds.stream()
                        .sorted(java.util.Comparator.comparing(KnowledgeSpaceId::value))
                        .map(spaceId -> new SpaceRoutingCandidate(
                                spaceId,
                                spaceId.value(),
                                ""
                        ))
                        .toList();
        RetrievalComponentRegistry components = new RetrievalComponentRegistry(
                List.of(new TerminologyServiceComponent(
                        new RetrievalComponentVersion(
                                "terminology-service",
                                "test",
                                "test-terminology",
                                "test-terminology-v1"
                        ),
                        terminologyService
                )),
                java.util.Optional.of(new FeedbackQueryPlannerComponent(
                        new RetrievalComponentVersion(
                                "feedback-query-planner",
                                "test",
                                "feedback-v1",
                                "v1"
                        ),
                        FeedbackQueryPlanner.unavailable()
                )),
                List.of(new RerankerComponent(
                        new RetrievalComponentVersion(
                                "reranker",
                                "test",
                                "configured-instance",
                                "v1"
                        ),
                        reranker
                )),
                List.of(),
                retrievers
        );
        ActiveIndexGenerationCatalog indexGenerations = (tenantId, spaceId) ->
                java.util.Optional.of(new ActiveIndexGeneration(
                        spaceId,
                        UUID.fromString("00000000-0000-0000-0000-000000000901"),
                        "test-index-v1"
                ));
        return new DefaultKnowledgeGateway(
                policy,
                activeRevisionGuard,
                queryAnalyzer,
                components,
                indexGenerations,
                new ReciprocalRankFusion(60),
                new DefaultEvidenceBuilder(),
                traces::add,
                catalog,
                SpaceRouter.deterministic(),
                testConfigurationStore(configuration),
                new RetrievalConfigurationResolver(),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                RetrievalObservationPublisher.noop(),
                new HmacSha256TextFingerprinter(
                        "test-only-retrieval-observation-key-0001",
                        "test-v1"
                ),
                executor,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC),
                0.5D,
                requestTimeout,
                requestTimeout.compareTo(Duration.ofSeconds(5)) < 0
                        ? requestTimeout : Duration.ofSeconds(5),
                queryPlannerTimeout,
                channelTimeout,
                rerankerTimeout,
                requestTimeout.compareTo(Duration.ofSeconds(8)) < 0
                        ? requestTimeout : Duration.ofSeconds(8)
        );
    }

    /** 返回与测试术语组件版本一致、但不产生 Q1 的确定性实现。 */
    private TerminologyService emptyTerminologyService() {
        return ignored -> TerminologyExpansionResult.none(
                "test",
                "test-terminology-v1"
        );
    }

    /** 创建保持旧测试场景语义、但不进入生产代码的完整物化配置。 */
    private RetrievalConfiguration testConfiguration(int maximumVariantsPerAttempt) {
        RetrievalConfiguration source = RetrievalConfiguration.deterministicBaseline();
        java.util.EnumMap<RetrievalConfiguration.ChainNode, Boolean> chain =
                new java.util.EnumMap<>(RetrievalConfiguration.ChainNode.class);
        for (RetrievalConfiguration.ChainNode node
                : RetrievalConfiguration.ChainNode.values()) {
            chain.put(node, false);
        }
        return new RetrievalConfiguration(
                new RetrievalConfiguration.FirstRound(true, "test-terminology", 16),
                new RetrievalConfiguration.Branches(
                        Math.min(16, Math.max(1, maximumVariantsPerAttempt)),
                        8,
                        source.branches().rrfConstant(),
                        source.branches().channels()
                ),
                new RetrievalConfiguration.Reranker(
                        true,
                        "test",
                        "configured-instance",
                        40,
                        8
                ),
                source.coverage(),
                1,
                chain,
                source.crossSpace()
        );
    }

    /** 只读物化配置端口只属于当前测试夹具。 */
    private SpaceRetrievalConfigurationStore testConfigurationStore(
            RetrievalConfiguration configuration
    ) {
        return new SpaceRetrievalConfigurationStore() {
            @Override
            public java.util.Optional<SpaceRetrievalConfiguration> findCurrent(
                    TenantId tenantId,
                    KnowledgeSpaceId spaceId
            ) {
                return java.util.Optional.of(materialized(tenantId, spaceId));
            }

            @Override
            public java.util.Optional<SpaceRetrievalConfiguration> findRevision(
                    TenantId tenantId,
                    KnowledgeSpaceId spaceId,
                    long revision
            ) {
                return revision == 1L
                        ? java.util.Optional.of(materialized(tenantId, spaceId))
                        : java.util.Optional.empty();
            }

            @Override
            public List<SpaceRetrievalConfiguration> history(
                    TenantId tenantId,
                    KnowledgeSpaceId spaceId,
                    int limit
            ) {
                return limit < 1
                        ? List.of() : List.of(materialized(tenantId, spaceId));
            }

            @Override
            public ActivationOutcome appendAndActivate(
                    SpaceRetrievalConfiguration ignored,
                    long expectedCurrentRevision
            ) {
                throw new UnsupportedOperationException("test configuration store is read-only");
            }

            private SpaceRetrievalConfiguration materialized(
                    TenantId tenantId,
                    KnowledgeSpaceId spaceId
            ) {
                return SpaceRetrievalConfiguration.create(
                        tenantId,
                        spaceId,
                        1L,
                        configuration,
                        new PrincipalId("test-system"),
                        Instant.EPOCH
                );
            }
        };
    }

    private Retriever blockingRetriever(CountDownLatch interrupted) {
        return new Retriever() {
            @Override
            public RetrievalComponentVersion componentVersion() {
                return retrieverVersion(RetrievalChannel.KEYWORD);
            }

            @Override
            public RetrievalChannel channel() {
                return RetrievalChannel.KEYWORD;
            }

            @Override
            public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interruptedFailure) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
    }

    /**
     * 创建测试查询。
     *
     * @param text 查询文本
     * @return 知识查询
     */
    private KnowledgeQuery query(String text) {
        return query(text, 5);
    }

    private KnowledgeQuery query(String text, int topK) {
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
                topK,
                Map.of(),
                dev.infinityknowledge.domain.retrieval.RetrievalConstraintInput.empty(),
                "",
                List.of(),
                RetrievalConfigurationOverride.empty(),
                RetrievalObservationPurpose.ONLINE
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
            @Override
            public RetrievalComponentVersion componentVersion() {
                return retrieverVersion(channel);
            }

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

    /**
     * 创建记录实际请求且不返回候选的 Retriever。
     */
    private Retriever capturingRetriever(
            RetrievalChannel channel,
            List<RetrievalRequest> captured
    ) {
        return new Retriever() {
            @Override
            public RetrievalComponentVersion componentVersion() {
                return retrieverVersion(channel);
            }

            @Override
            public RetrievalChannel channel() {
                return channel;
            }

            @Override
            public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
                captured.add(request);
                return List.of();
            }
        };
    }

    /** 返回与物理通道绑定的测试 Retriever 组件合同。 */
    private RetrievalComponentVersion retrieverVersion(RetrievalChannel channel) {
        return new RetrievalComponentVersion(
                "retriever-" + channel.name().toLowerCase(java.util.Locale.ROOT),
                "test",
                channel.name().toLowerCase(java.util.Locale.ROOT),
                "v1"
        );
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
