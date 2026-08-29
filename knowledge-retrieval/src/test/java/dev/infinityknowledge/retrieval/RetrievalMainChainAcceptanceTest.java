package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.EvidenceRequirement;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalConstraintInput;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationOverride;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.retrieval.evidence.DefaultEvidenceBuilder;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.CoverageJudgeComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.FeedbackQueryPlannerComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.RerankerComponent;
import dev.infinityknowledge.retrieval.fusion.FusedCandidate;
import dev.infinityknowledge.retrieval.fusion.ReciprocalRankFusion;
import dev.infinityknowledge.retrieval.observation.HmacSha256TextFingerprinter;
import dev.infinityknowledge.retrieval.query.DefaultQueryAnalyzer;
import dev.infinityknowledge.retrieval.query.ResolvedConstraints;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ActiveIndexGeneration;
import dev.infinityknowledge.spi.indexing.ActiveIndexGenerationCatalog;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import dev.infinityknowledge.spi.retrieval.CoverageJudgmentResult;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanningResult;
import dev.infinityknowledge.spi.retrieval.QueryVariant;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;
import dev.infinityknowledge.spi.retrieval.RerankResult;
import dev.infinityknowledge.spi.retrieval.Reranker;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.RetrievalComponentVersion;
import dev.infinityknowledge.spi.retrieval.RetrievalSpaceCatalog;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingCandidate;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingResult;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定检索主链的配置、固定优化顺序、跨 Space 证据记忆和稳定降级语义。
 *
 * <p>这些测试刻意通过既有 Gateway 和阶段对象验收，不为测试引入“激活 Space”或
 * 可由用户重排的 Chain 对象。</p>
 */
class RetrievalMainChainAcceptanceTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE_A = new KnowledgeSpaceId("space-a");
    private static final KnowledgeSpaceId SPACE_B = new KnowledgeSpaceId("space-b");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-26T00:00:00Z"),
            ZoneOffset.UTC
    );
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String QUERY_TEXT = "订单服务 ERR-1001 如何恢复";
    private static final String CANDIDATE_CONTENT =
            "检查连接池、下游依赖和最近配置变更。";

    /** 请求覆盖只改变本次有效配置，并以新指纹和实际分支预算进入权威主链。 */
    @Test
    void appliesStronglyTypedRequestOverrideWithoutChangingSpaceRevision() {
        RetrievalConfiguration sourceConfiguration = configuration(
                true,
                false,
                1,
                Set.of()
        );
        SpaceRetrievalConfiguration source = materialized(SPACE_A, sourceConfiguration);
        RetrievalConfigurationOverride override = new RetrievalConfigurationOverride(
                null,
                new RetrievalConfigurationOverride.BranchesOverride(
                        null,
                        null,
                        null,
                        Map.of(
                                RetrievalChannel.KEYWORD,
                                new RetrievalConfigurationOverride.BranchOverride(
                                        null,
                                        3,
                                        null
                                )
                        )
                ),
                null,
                new RetrievalConfigurationOverride.CoverageOverride(
                        false,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null,
                null,
                null
        );
        AtomicReference<RetrievalRequest> captured = new AtomicReference<>();
        AtomicInteger coverageCalls = new AtomicInteger();
        Retriever retriever = retriever(request -> {
            captured.set(request);
            return List.of(candidate(SPACE_A, UUID.randomUUID(), 1));
        });
        CoverageJudge coverageJudge = request -> {
            coverageCalls.incrementAndGet();
            throw new AssertionError("请求已关闭 Coverage，不应调用 Judge");
        };
        RetrievalConfigurationHardLimits hardLimits =
                RetrievalConfigurationHardLimits.conservativeDefaults();
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever,
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(SPACE_A, source)),
                hardLimits,
                coverageJudge,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                override,
                List.of(new EvidenceRequirement("fact", "需要覆盖的事实")),
                5
        ));

        String expectedFingerprint = new RetrievalConfigurationResolver()
                .resolve(source, override, hardLimits)
                .fingerprint();
        assertEquals(3, captured.get().plan().candidateLimit());
        assertEquals(0, coverageCalls.get());
        assertEquals(RetrievalTerminalStatus.NOT_EVALUATED, result.terminalStatus());
        assertEquals(RetrievalStopReason.COVERAGE_DISABLED, result.stopReason());
        assertEquals(List.of(expectedFingerprint), result.configurationFingerprints());
        assertNotEquals(source.fingerprint(), expectedFingerprint);
        assertEquals(1L, source.revision());
        assertEquals(sourceConfiguration, source.configuration());
    }

    /** 固定枚举顺序优先于 Map 构造顺序，不适用的约束节点只能被程序跳过。 */
    @Test
    void executesEnabledChainNodesInTheSystemFixedOrder() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                8,
                EnumSet.allOf(RetrievalConfiguration.ChainNode.class)
        );
        FusedCandidate memory = new FusedCandidate(
                candidate(SPACE_A, UUID.randomUUID(), 1),
                Set.of(RetrievalChannel.KEYWORD),
                1.0D,
                1.0D,
                Map.of("test", 1.0D),
                1,
                null,
                ""
        );
        OptimizationChainRunner runner = new OptimizationChainRunner();
        EnumSet<RetrievalConfiguration.ChainNode> consumed = EnumSet.noneOf(
                RetrievalConfiguration.ChainNode.class
        );
        List<RetrievalConfiguration.ChainNode> selected = new ArrayList<>();
        List<RetrievalStepTrace> steps = new ArrayList<>();

        for (int index = 0; index < 5; index++) {
            RetrievalConfiguration.ChainNode node = runner.next(
                    new OptimizationChainRunner.Context(
                            configuration,
                            SPACE_A,
                            0,
                            List.of(SPACE_A, SPACE_B),
                            1,
                            0.2D,
                            List.of("仍缺少适用范围"),
                            List.of(memory),
                            ResolvedConstraints.empty(),
                            consumed
                    ),
                    steps
            ).orElseThrow();
            selected.add(node);
            consumed.add(node);
        }

        assertEquals(
                List.of(
                        RetrievalConfiguration.ChainNode.GAP_QUERY,
                        RetrievalConfiguration.ChainNode.PRF,
                        RetrievalConfiguration.ChainNode.STEP_BACK,
                        RetrievalConfiguration.ChainNode.HYDE,
                        RetrievalConfiguration.ChainNode.NEXT_SPACE
                ),
                selected
        );
        assertTrue(steps.stream().anyMatch(step ->
                "CHAIN_NODE_RELAX_CONSTRAINTS".equals(step.name())
                        && "SKIPPED".equals(step.status())
        ));
        assertTrue(steps.stream().anyMatch(step ->
                "CHAIN_NODE_NARROW_CONSTRAINTS".equals(step.name())
                        && "SKIPPED".equals(step.status())
        ));
    }

    /** 普通 filters 是调用方硬约束；即使启用 RELAX 也不得进入可放宽集合。 */
    @Test
    void neverRelaxesCallerHardFilter() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                2,
                Set.of(RetrievalConfiguration.ChainNode.RELAX_CONSTRAINTS)
        );
        List<Map<String, String>> executedFilters = new ArrayList<>();
        CoverageJudge judge = request -> new CoverageJudgmentResult(
                0.20D,
                List.of("结果过少"),
                request.candidates().stream().map(value -> value.candidateId()).toList(),
                "INSUFFICIENT"
        );
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> {
                    executedFilters.add(request.query().filters());
                    return List.of(candidate(SPACE_A, UUID.randomUUID(), 1));
                }),
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("fact", "定位恢复办法")),
                5,
                Map.of("language", "zh"),
                RetrievalConstraintInput.empty()
        ));

        assertEquals(List.of(Map.of("language", "zh")), executedFilters);
        assertEquals(RetrievalTerminalStatus.INSUFFICIENT, result.terminalStatus());
        assertEquals(
                RetrievalStopReason.NO_APPLICABLE_OPTIMIZATION_NODE,
                result.stopReason()
        );
    }

    /** 调用方显式标记的软过滤可被 RELAX 移除，同时硬过滤必须继续生效。 */
    @Test
    void relaxesOnlyExplicitSoftConstraintAndRerunsOriginalQuery() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                2,
                Set.of(RetrievalConfiguration.ChainNode.RELAX_CONSTRAINTS)
        );
        List<String> executedQueries = new ArrayList<>();
        List<Map<String, String>> executedFilters = new ArrayList<>();
        AtomicInteger coverageCalls = new AtomicInteger();
        CoverageJudge judge = request -> {
            int call = coverageCalls.incrementAndGet();
            return new CoverageJudgmentResult(
                    call == 1 ? 0.20D : 0.90D,
                    call == 1 ? List.of("过滤后结果过少") : List.of(),
                    request.candidates().stream().map(value -> value.candidateId()).toList(),
                    call == 1 ? "INSUFFICIENT" : "SUFFICIENT"
            );
        };
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> {
                    executedQueries.add(request.query().text());
                    executedFilters.add(request.query().filters());
                    return List.of(candidate(SPACE_A, UUID.randomUUID(), 1));
                }),
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("fact", "定位恢复办法")),
                5,
                Map.of("sourceType", "runbook"),
                new RetrievalConstraintInput(
                        Map.of("language", "zh"),
                        Map.of()
                )
        ));

        assertEquals(List.of(QUERY_TEXT, QUERY_TEXT), executedQueries);
        assertEquals(
                List.of(
                        Map.of("sourceType", "runbook", "language", "zh"),
                        Map.of("sourceType", "runbook")
                ),
                executedFilters
        );
        assertEquals(RetrievalTerminalStatus.SUFFICIENT, result.terminalStatus());
    }

    /** NARROW 只能应用 Agent 已给出的候选，不能由 Runtime 发明新过滤条件。 */
    @Test
    void narrowsWithCallerCandidateAndRerunsOriginalQuery() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                2,
                Set.of(RetrievalConfiguration.ChainNode.NARROW_CONSTRAINTS)
        );
        List<String> executedQueries = new ArrayList<>();
        List<Map<String, String>> executedFilters = new ArrayList<>();
        AtomicInteger coverageCalls = new AtomicInteger();
        CoverageJudge judge = request -> {
            int call = coverageCalls.incrementAndGet();
            return new CoverageJudgmentResult(
                    call == 1 ? 0.20D : 0.90D,
                    call == 1 ? List.of("主题过宽") : List.of(),
                    request.candidates().stream().map(value -> value.candidateId()).toList(),
                    call == 1 ? "INSUFFICIENT" : "SUFFICIENT"
            );
        };
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> {
                    executedQueries.add(request.query().text());
                    executedFilters.add(request.query().filters());
                    return List.of(candidate(SPACE_A, UUID.randomUUID(), 1));
                }),
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("fact", "定位恢复办法")),
                5,
                Map.of("language", "zh"),
                new RetrievalConstraintInput(
                        Map.of(),
                        Map.of("sourceType", "runbook")
                )
        ));

        assertEquals(List.of(QUERY_TEXT, QUERY_TEXT), executedQueries);
        assertEquals(
                List.of(
                        Map.of("language", "zh"),
                        Map.of("language", "zh", "sourceType", "runbook")
                ),
                executedFilters
        );
        assertEquals(RetrievalTerminalStatus.SUFFICIENT, result.terminalStatus());
    }

    /** Coverage 达到 Space 阈值时立即以充分终态结束，不消耗后续优化节点。 */
    @Test
    void terminatesAsSufficientWhenCoverageReachesThreshold() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                2,
                Set.of(RetrievalConfiguration.ChainNode.STEP_BACK)
        );
        UUID chunkId = UUID.randomUUID();
        AtomicInteger coverageCalls = new AtomicInteger();
        CoverageJudge judge = request -> {
            coverageCalls.incrementAndGet();
            return new CoverageJudgmentResult(
                    0.90D,
                    List.of(),
                    request.candidates().stream().map(value -> value.candidateId()).toList(),
                    "SUFFICIENT"
            );
        };
        List<RetrievalTrace> traces = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> List.of(candidate(SPACE_A, chunkId, 1))),
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                traces
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("fact", "验证完整处理办法")),
                5
        ));

        assertEquals(1, coverageCalls.get());
        assertEquals(RetrievalTerminalStatus.SUFFICIENT, result.terminalStatus());
        assertEquals(
                RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED,
                result.stopReason()
        );
        assertEquals(List.of(chunkId), evidenceChunkIds(result));
        assertFalse(traces.getFirst().steps().stream().anyMatch(step ->
                step.name().startsWith("CHAIN_NODE_")
        ));
    }

    /** Coverage 技术重试后即使得到可信结论，也必须保留本次执行发生过降级的事实。 */
    @Test
    void marksSuccessfulCoverageRetryAsDegraded() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                2,
                Set.of()
        );
        AtomicInteger calls = new AtomicInteger();
        CoverageJudge judge = request -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary provider failure");
            }
            return new CoverageJudgmentResult(
                    0.90D,
                    List.of(),
                    request.candidates().stream()
                            .map(value -> value.candidateId())
                            .toList(),
                    "SUFFICIENT"
            );
        };
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> List.of(candidate(
                        SPACE_A,
                        UUID.randomUUID(),
                        1
                ))),
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("recovery", "恢复步骤")),
                5
        ));

        assertEquals(2, calls.get());
        assertEquals(RetrievalTerminalStatus.SUFFICIENT, result.terminalStatus());
        assertTrue(result.degraded());
        assertTrue(result.warnings().contains("COVERAGE_JUDGE_RETRIED"));
    }

    /** 不充分结果在唯一检索预算耗尽后终止，不能越过预算继续调用反馈规划。 */
    @Test
    void terminatesAsInsufficientWhenRetrievalAttemptBudgetIsExhausted() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                1,
                Set.of()
        );
        AtomicInteger retrievalCalls = new AtomicInteger();
        AtomicInteger coverageCalls = new AtomicInteger();
        CoverageJudge judge = request -> {
            coverageCalls.incrementAndGet();
            return new CoverageJudgmentResult(
                    0.20D,
                    List.of("缺少故障恢复步骤"),
                    request.candidates().stream().map(value -> value.candidateId()).toList(),
                    "INSUFFICIENT"
            );
        };
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> {
                    retrievalCalls.incrementAndGet();
                    return List.of(candidate(SPACE_A, UUID.randomUUID(), 1));
                }),
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("recovery", "恢复步骤")),
                5
        ));

        assertEquals(1, retrievalCalls.get());
        assertEquals(1, coverageCalls.get());
        assertEquals(RetrievalTerminalStatus.INSUFFICIENT, result.terminalStatus());
        assertEquals(
                RetrievalStopReason.RETRIEVAL_BUDGET_EXHAUSTED,
                result.stopReason()
        );
    }

    /** GAP_QUERY 成功后只执行局部变体，Q0 仅作为精排和 Coverage 锚点。 */
    @Test
    void executesGeneratedFeedbackVariantWithoutRepeatingOriginalQuery() {
        RetrievalConfiguration configuration = configuration(
                true,
                true,
                2,
                Set.of(RetrievalConfiguration.ChainNode.GAP_QUERY)
        );
        List<String> retrievedQueries = new ArrayList<>();
        List<String> rerankQueries = new ArrayList<>();
        AtomicInteger coverageCalls = new AtomicInteger();
        CoverageJudge judge = request -> {
            int call = coverageCalls.incrementAndGet();
            return new CoverageJudgmentResult(
                    call == 1 ? 0.20D : 0.90D,
                    call == 1 ? List.of("缺少恢复步骤") : List.of(),
                    request.candidates().stream()
                            .map(value -> value.candidateId())
                            .toList(),
                    call == 1 ? "INSUFFICIENT" : "SUFFICIENT"
            );
        };
        FeedbackQueryPlanner feedbackPlanner = request -> {
            assertEquals(QueryVariantKind.GAP_QUERY, request.strategy());
            assertEquals(List.of("缺少恢复步骤"), request.missingGaps());
            return new FeedbackQueryPlanningResult(
                    new QueryVariant(
                            "gap-1",
                            QueryVariantKind.GAP_QUERY,
                            QUERY_TEXT + " 恢复步骤"
                    ),
                    "test",
                    "feedback-v1"
            );
        };
        List<RetrievalObservation> observations = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> {
                    retrievedQueries.add(request.query().text());
                    return List.of(candidate(SPACE_A, UUID.randomUUID(), 1));
                }),
                (rerankQuery, candidates, limit) -> {
                    rerankQueries.add(rerankQuery);
                    List<RetrievalCandidate> ordered = candidates.stream()
                            .limit(limit)
                            .toList();
                    Map<UUID, Double> scores = ordered.stream().collect(
                            java.util.stream.Collectors.toUnmodifiableMap(
                                    RetrievalCandidate::chunkId,
                                    ignored -> 1.0D
                            )
                    );
                    return new RerankResult(
                            ordered,
                            scores,
                            ordered.size(),
                            "TEST_MODEL"
                    );
                },
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                feedbackPlanner,
                observations::add,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("recovery", "恢复步骤")),
                5
        ));

        assertEquals(List.of(QUERY_TEXT, QUERY_TEXT + " 恢复步骤"), retrievedQueries);
        assertEquals(
                List.of(
                        QUERY_TEXT,
                        QUERY_TEXT + "\n当前轮检索目标：" + QUERY_TEXT + " 恢复步骤"
                ),
                rerankQueries
        );
        assertEquals(RetrievalTerminalStatus.SUFFICIENT, result.terminalStatus());
        assertEquals(2, coverageCalls.get());
        assertTrue(observations.stream().anyMatch(observation ->
                observation.payload()
                        instanceof RetrievalObservationPayload.ChainNodeCompleted completed
                        && "GAP_QUERY".equals(completed.node())
                        && completed.coverageBefore().present()
                        && completed.coverageAfter().present()
                        && completed.coverageAfter().value()
                                > completed.coverageBefore().value()
                        && completed.usage().requestCount() == 1
        ));
        assertTrue(observations.stream().anyMatch(observation ->
                observation.payload()
                        instanceof RetrievalObservationPayload.QueryPlanningCompleted completed
                        && completed.variants().stream().anyMatch(variant ->
                                QueryVariantKind.GAP_QUERY.name().equals(variant.kind())
                        )
                        && completed.usage().requestCount() == 0
        ));
    }

    /**
     * 模型只返回 Space 有序列表；NEXT_SPACE 用下标切换，并把前一 Space 的证据带入下一轮。
     */
    @Test
    void followsModelSpaceOrderAndRetainsEvidenceAcrossNextSpace() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                2,
                Set.of(RetrievalConfiguration.ChainNode.NEXT_SPACE)
        );
        UUID firstSpaceChunk = UUID.randomUUID();
        UUID secondSpaceChunk = UUID.randomUUID();
        AtomicReference<List<KnowledgeSpaceId>> modelInput = new AtomicReference<>();
        SpaceRouter router = request -> {
            modelInput.set(request.allowedSpaces().stream()
                    .map(SpaceRoutingCandidate::spaceId)
                    .toList());
            return new SpaceRoutingResult(
                    List.of(SPACE_B, SPACE_A),
                    "test",
                    "space-ranker-v1"
            );
        };
        AtomicInteger coverageCalls = new AtomicInteger();
        CoverageJudge judge = request -> {
            int call = coverageCalls.incrementAndGet();
            List<UUID> retained = request.candidates().stream()
                    .map(value -> value.candidateId())
                    .toList();
            if (call == 1) {
                assertEquals(List.of(SPACE_B), request.candidates().stream()
                        .map(value -> value.spaceId())
                        .toList());
                return new CoverageJudgmentResult(
                        0.30D,
                        List.of("还需要基础空间的证据"),
                        retained,
                        "NEED_NEXT_SPACE"
                );
            }
            assertEquals(List.of(SPACE_B, SPACE_A), request.candidates().stream()
                    .map(value -> value.spaceId())
                    .toList());
            return new CoverageJudgmentResult(
                    0.95D,
                    List.of(),
                    retained,
                    "SUFFICIENT"
            );
        };
        Retriever retriever = retriever(request -> {
            KnowledgeSpaceId current = request.query().spaceIds().iterator().next();
            return List.of(current.equals(SPACE_B)
                    ? candidate(SPACE_B, firstSpaceChunk, 1)
                    : candidate(SPACE_A, secondSpaceChunk, 1));
        });
        RetrievalSpaceCatalog catalog = (tenantId, allowedSpaceIds) -> List.of(
                new SpaceRoutingCandidate(SPACE_B, "业务知识", "业务故障手册"),
                new SpaceRoutingCandidate(SPACE_A, "基础知识", "通用基础规则")
        );
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A, SPACE_B),
                retriever,
                Reranker.passthrough(),
                catalog,
                router,
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration),
                        SPACE_B,
                        materialized(SPACE_B, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A, SPACE_B),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("complete", "完整回答处理办法")),
                5
        ));

        assertEquals(List.of(SPACE_A, SPACE_B), modelInput.get());
        assertEquals(List.of(SPACE_B, SPACE_A), result.visitedSpaceIds());
        assertEquals(2, result.configurationFingerprints().size());
        assertEquals(2, coverageCalls.get());
        assertEquals(RetrievalTerminalStatus.SUFFICIENT, result.terminalStatus());
        assertEquals(
                List.of(firstSpaceChunk, secondSpaceChunk),
                evidenceChunkIds(result)
        );
    }

    /** 精排器整体失败时保留完整 RRF 顺序，而不是返回局部模型结果或 Retriever 原顺序。 */
    @Test
    void fallsBackAsAWholeToRrfOrderWhenRerankerFails() {
        RetrievalConfiguration configuration = configuration(
                false,
                true,
                1,
                Set.of()
        );
        UUID rrfFirst = UUID.randomUUID();
        UUID rrfSecond = UUID.randomUUID();
        RetrievalCandidate rankOne = candidate(SPACE_A, rrfFirst, 1);
        RetrievalCandidate rankTwo = candidate(SPACE_A, rrfSecond, 2);
        List<RetrievalTrace> traces = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> List.of(rankTwo, rankOne)),
                (query, candidates, limit) -> {
                    throw new IllegalStateException("模型精排整体失败");
                },
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                CoverageJudge.unavailable(),
                traces
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(),
                2
        ));

        assertEquals(List.of(rrfFirst, rrfSecond), evidenceChunkIds(result));
        assertTrue(result.warnings().contains("RERANKER_UNAVAILABLE"));
        assertTrue(traces.getFirst().steps().stream().anyMatch(step ->
                "RERANK".equals(step.name()) && "DEGRADED".equals(step.status())
        ));
    }

    /**
     * 收集型发布器必须得到连续且首尾唯一的执行事实，跨 Space 时每个 visit 使用自己的配置指纹。
     */
    @Test
    void publishesContinuousSafeObservationsWithVisitScopedFingerprints() {
        RetrievalConfiguration configuration = configuration(
                true,
                false,
                2,
                Set.of(RetrievalConfiguration.ChainNode.NEXT_SPACE)
        );
        UUID firstSpaceChunk = UUID.randomUUID();
        UUID secondSpaceChunk = UUID.randomUUID();
        AtomicInteger coverageCalls = new AtomicInteger();
        CoverageJudge judge = request -> {
            int call = coverageCalls.incrementAndGet();
            return new CoverageJudgmentResult(
                    call == 1 ? 0.30D : 0.95D,
                    call == 1 ? List.of("需要下一空间证据") : List.of(),
                    request.candidates().stream()
                            .map(value -> value.candidateId())
                            .toList(),
                    call == 1 ? "NEED_NEXT_SPACE" : "SUFFICIENT"
            );
        };
        Retriever retriever = retriever(request -> {
            KnowledgeSpaceId current = request.query().spaceIds().iterator().next();
            return List.of(current.equals(SPACE_B)
                    ? candidate(SPACE_B, firstSpaceChunk, 1)
                    : candidate(SPACE_A, secondSpaceChunk, 1));
        });
        List<RetrievalObservation> observations = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A, SPACE_B),
                retriever,
                Reranker.passthrough(),
                stableCatalog(),
                request -> new SpaceRoutingResult(
                        List.of(SPACE_B, SPACE_A),
                        "test",
                        "space-ranker-v1"
                ),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration),
                        SPACE_B,
                        materialized(SPACE_B, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                judge,
                observations::add,
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A, SPACE_B),
                RetrievalConfigurationOverride.empty(),
                List.of(new EvidenceRequirement("complete", "完整回答处理办法")),
                5
        ));

        assertEquals(
                java.util.stream.LongStream.range(0, observations.size())
                        .boxed()
                        .toList(),
                observations.stream().map(RetrievalObservation::sequence).toList()
        );
        assertEquals(1L, observations.stream().filter(value ->
                value.stage() == RetrievalObservationStage.EXECUTION_STARTED
        ).count());
        assertEquals(1L, observations.stream().filter(RetrievalObservation::terminal).count());
        RetrievalObservation started = observations.getFirst();
        RetrievalObservation terminal = observations.getLast();
        assertEquals(0L, started.sequence());
        assertEquals(RetrievalObservationStatus.STARTED, started.status());
        assertEquals(RetrievalObservationStage.EXECUTION_TERMINAL, terminal.stage());
        assertEquals(RetrievalObservationStatus.SUCCEEDED, terminal.status());
        assertTrue(terminal.payload()
                instanceof RetrievalObservationPayload.ExecutionTerminal payload
                && payload.terminalStatus() == RetrievalTerminalStatus.SUFFICIENT
                && payload.stopReason()
                        == RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED
                && payload.retrievalAttemptCount() == 2);
        assertTrue(observations.stream().allMatch(value ->
                value.executionId().equals(started.executionId())
                        && value.requestId().equals(started.requestId())
        ));

        Set<RetrievalObservationStage> emittedStages = observations.stream()
                .map(RetrievalObservation::stage)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertTrue(emittedStages.containsAll(Set.of(
                RetrievalObservationStage.EXECUTION_STARTED,
                RetrievalObservationStage.SPACE_ROUTING,
                RetrievalObservationStage.CONFIGURATION_RESOLVED,
                RetrievalObservationStage.QUERY_ANALYSIS,
                RetrievalObservationStage.QUERY_PLANNING,
                RetrievalObservationStage.RETRIEVAL_PLAN,
                RetrievalObservationStage.RETRIEVAL_BRANCH,
                RetrievalObservationStage.FUSION,
                RetrievalObservationStage.RERANK,
                RetrievalObservationStage.COVERAGE_CHECK,
                RetrievalObservationStage.CHAIN_NODE_EVALUATED,
                RetrievalObservationStage.CHAIN_NODE_COMPLETED,
                RetrievalObservationStage.SPACE_CHANGED,
                RetrievalObservationStage.EVIDENCE_BUILD,
                RetrievalObservationStage.EXECUTION_TERMINAL
        )));

        List<RetrievalObservation> configurationEvents = observations.stream()
                .filter(value ->
                        value.stage() == RetrievalObservationStage.CONFIGURATION_RESOLVED
                )
                .toList();
        assertEquals(List.of(0, 1), configurationEvents.stream()
                .map(RetrievalObservation::visitIndex)
                .toList());
        assertEquals(result.configurationFingerprints(), configurationEvents.stream()
                .map(RetrievalObservation::configFingerprint)
                .toList());
        assertTrue(configurationEvents.stream().allMatch(value ->
                value.payload() instanceof RetrievalObservationPayload.ConfigurationResolved payload
                        && payload.sourceRevision() == 1L
        ));
        for (RetrievalObservation observation : observations) {
            if (observation.stage() == RetrievalObservationStage.EXECUTION_STARTED
                    || observation.stage() == RetrievalObservationStage.SPACE_ROUTING) {
                assertEquals(
                        RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        observation.configFingerprint()
                );
            } else {
                assertEquals(
                        result.configurationFingerprints().get(observation.visitIndex()),
                        observation.configFingerprint()
                );
            }
        }

        String serializedVisibleForm = observations.toString();
        assertFalse(serializedVisibleForm.contains(QUERY_TEXT));
        assertFalse(serializedVisibleForm.contains(CANDIDATE_CONTENT));
        assertTrue(observations.stream().noneMatch(value ->
                value.payload().toString().contains(QUERY_TEXT)
                        || value.payload().toString().contains(CANDIDATE_CONTENT)
        ));
    }

    /** 观测发布器属于旁路；连续抛错只能标记降级，不能吞掉已经完成的业务证据。 */
    @Test
    void failsOpenWhenObservationPublisherThrows() {
        RetrievalConfiguration configuration = configuration(
                false,
                false,
                1,
                Set.of()
        );
        UUID chunkId = UUID.randomUUID();
        AtomicInteger publishCalls = new AtomicInteger();
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> List.of(candidate(SPACE_A, chunkId, 1))),
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                CoverageJudge.unavailable(),
                ignored -> {
                    publishCalls.incrementAndGet();
                    throw new IllegalStateException("observation sink unavailable");
                },
                new ArrayList<>()
        );

        EvidenceBundle result = gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(),
                5
        ));

        assertTrue(publishCalls.get() > 1);
        assertEquals(List.of(chunkId), evidenceChunkIds(result));
        assertEquals(RetrievalTerminalStatus.NOT_EVALUATED, result.terminalStatus());
        assertEquals(RetrievalStopReason.COVERAGE_DISABLED, result.stopReason());
        assertTrue(result.degraded());
        assertEquals(1L, result.warnings().stream()
                .filter("OBSERVATION_PUBLISH_FAILED"::equals)
                .count());
    }

    /** 关键阶段异常也必须闭合为技术失败终态，且不能伪造已经消费的检索轮次。 */
    @Test
    void closesObservationAsTechnicalFailureBeforeFirstRetrievalAttempt() {
        RetrievalConfiguration configuration = configuration(
                false,
                false,
                1,
                Set.of()
        );
        List<RetrievalObservation> observations = new ArrayList<>();
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> List.of()),
                Reranker.passthrough(),
                (tenantId, allowedSpaceIds) -> List.of(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                CoverageJudge.unavailable(),
                observations::add,
                new ArrayList<>()
        );

        assertThrows(IllegalStateException.class, () -> gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(),
                5
        )));

        RetrievalObservation failedStage = observations.get(observations.size() - 2);
        assertEquals(RetrievalObservationStage.STAGE_FAILURE, failedStage.stage());
        assertEquals(RetrievalObservationStatus.FAILED, failedStage.status());
        assertEquals("SPACE_ROUTING_FAILED", failedStage.reasonCode());
        assertTrue(failedStage.payload()
                instanceof RetrievalObservationPayload.StageFailed payload
                && "SPACE_ROUTING".equals(payload.failedComponent()));
        RetrievalObservation terminal = observations.getLast();
        assertEquals(RetrievalObservationStatus.FAILED, terminal.status());
        assertTrue(terminal.payload()
                instanceof RetrievalObservationPayload.ExecutionTerminal payload
                && payload.terminalStatus() == RetrievalTerminalStatus.TECHNICAL_FAILED
                && payload.stopReason() == RetrievalStopReason.TECHNICAL_FAILURE
                && payload.retrievalAttemptCount() == 0);
    }

    /** Retriever 执行后的安全校验失败，也必须先闭合分支且不能泄露候选身份。 */
    @Test
    void closesEveryRetrievalBranchWhenPostValidationFails() {
        RetrievalConfiguration configuration = configuration(
                false,
                false,
                1,
                Set.of()
        );
        List<RetrievalObservation> observations = new ArrayList<>();
        ActiveRevisionGuard failingGuard = new ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId tenantId,
                    DocumentId documentId,
                    UUID revisionId
            ) {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<RetrievalCandidate> candidates
            ) {
                throw new IllegalStateException("controlled active-revision failure");
            }
        };
        DefaultKnowledgeGateway gateway = gateway(
                Set.of(SPACE_A),
                retriever(request -> List.of(candidate(SPACE_A, UUID.randomUUID(), 1))),
                Reranker.passthrough(),
                stableCatalog(),
                SpaceRouter.deterministic(),
                configurationStore(Map.of(
                        SPACE_A,
                        materialized(SPACE_A, configuration)
                )),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                CoverageJudge.unavailable(),
                FeedbackQueryPlanner.unavailable(),
                observations::add,
                new ArrayList<>(),
                failingGuard
        );

        assertThrows(IllegalStateException.class, () -> gateway.retrieve(query(
                Set.of(SPACE_A),
                RetrievalConfigurationOverride.empty(),
                List.of(),
                5
        )));

        List<RetrievalObservation> branches = observations.stream()
                .filter(value -> value.stage() == RetrievalObservationStage.RETRIEVAL_BRANCH)
                .toList();
        assertEquals(1, branches.size());
        assertEquals(RetrievalObservationStatus.FAILED, branches.getFirst().status());
        assertEquals(
                "RETRIEVER_OUTPUT_VALIDATION_FAILED",
                branches.getFirst().reasonCode()
        );
        assertTrue(branches.getFirst().payload()
                instanceof RetrievalObservationPayload.RetrievalBranchCompleted payload
                && payload.candidates().isEmpty());
        assertEquals(
                RetrievalObservationStage.STAGE_FAILURE,
                observations.get(observations.size() - 2).stage()
        );
        assertEquals(
                RetrievalObservationStage.EXECUTION_TERMINAL,
                observations.getLast().stage()
        );
    }

    /** 创建使用当前生产构造签名、同步执行器和确定性时钟的 Gateway。 */
    private DefaultKnowledgeGateway gateway(
            Set<KnowledgeSpaceId> allowedSpaces,
            Retriever retriever,
            Reranker reranker,
            RetrievalSpaceCatalog catalog,
            SpaceRouter router,
            SpaceRetrievalConfigurationStore configurationStore,
            RetrievalConfigurationHardLimits hardLimits,
            CoverageJudge coverageJudge,
            List<RetrievalTrace> traces
    ) {
        return gateway(
                allowedSpaces,
                retriever,
                reranker,
                catalog,
                router,
                configurationStore,
                hardLimits,
                coverageJudge,
                RetrievalObservationPublisher.noop(),
                traces
        );
    }

    /** 允许观测验收注入收集型或故障型发布器。 */
    private DefaultKnowledgeGateway gateway(
            Set<KnowledgeSpaceId> allowedSpaces,
            Retriever retriever,
            Reranker reranker,
            RetrievalSpaceCatalog catalog,
            SpaceRouter router,
            SpaceRetrievalConfigurationStore configurationStore,
            RetrievalConfigurationHardLimits hardLimits,
            CoverageJudge coverageJudge,
            RetrievalObservationPublisher observationPublisher,
            List<RetrievalTrace> traces
    ) {
        return gateway(
                allowedSpaces,
                retriever,
                reranker,
                catalog,
                router,
                configurationStore,
                hardLimits,
                coverageJudge,
                FeedbackQueryPlanner.unavailable(),
                observationPublisher,
                traces,
                allowAllRevisions()
        );
    }

    /** 允许反馈节点验收注入真正生成局部 Variant 的 Planner。 */
    private DefaultKnowledgeGateway gateway(
            Set<KnowledgeSpaceId> allowedSpaces,
            Retriever retriever,
            Reranker reranker,
            RetrievalSpaceCatalog catalog,
            SpaceRouter router,
            SpaceRetrievalConfigurationStore configurationStore,
            RetrievalConfigurationHardLimits hardLimits,
            CoverageJudge coverageJudge,
            FeedbackQueryPlanner feedbackQueryPlanner,
            RetrievalObservationPublisher observationPublisher,
            List<RetrievalTrace> traces
    ) {
        return gateway(
                allowedSpaces,
                retriever,
                reranker,
                catalog,
                router,
                configurationStore,
                hardLimits,
                coverageJudge,
                feedbackQueryPlanner,
                observationPublisher,
                traces,
                allowAllRevisions()
        );
    }

    /** 允许安全校验失败验收注入严格的活动修订守卫。 */
    private DefaultKnowledgeGateway gateway(
            Set<KnowledgeSpaceId> allowedSpaces,
            Retriever retriever,
            Reranker reranker,
            RetrievalSpaceCatalog catalog,
            SpaceRouter router,
            SpaceRetrievalConfigurationStore configurationStore,
            RetrievalConfigurationHardLimits hardLimits,
            CoverageJudge coverageJudge,
            FeedbackQueryPlanner feedbackQueryPlanner,
            RetrievalObservationPublisher observationPublisher,
            List<RetrievalTrace> traces,
            ActiveRevisionGuard activeRevisionGuard
    ) {
        RetrievalComponentRegistry components = new RetrievalComponentRegistry(
                List.of(),
                Optional.of(new FeedbackQueryPlannerComponent(
                        new RetrievalComponentVersion(
                                "feedback-query-planner",
                                "test",
                                "feedback-v1",
                                "v1"
                        ),
                        feedbackQueryPlanner
                )),
                List.of(new RerankerComponent(
                        new RetrievalComponentVersion(
                                "reranker",
                                "test",
                                "reranker-v1",
                                "v1"
                        ),
                        reranker
                )),
                List.of(new CoverageJudgeComponent(
                        new RetrievalComponentVersion(
                                "coverage-judge",
                                "test",
                                "coverage-v1",
                                "v1"
                        ),
                        coverageJudge
                )),
                List.of(retriever)
        );
        ActiveIndexGenerationCatalog indexGenerations = (tenantId, spaceId) ->
                Optional.of(new ActiveIndexGeneration(
                        spaceId,
                        UUID.nameUUIDFromBytes(
                                ("test-index:" + spaceId.value()).getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8
                                )
                        ),
                        "test-index-v1"
                ));
        return new DefaultKnowledgeGateway(
                (principal, requested) -> AccessScope.all(
                        principal.tenantId(),
                        allowedSpaces
                ),
                activeRevisionGuard,
                new DefaultQueryAnalyzer(5, Set.of(RetrievalChannel.KEYWORD)),
                components,
                indexGenerations,
                new ReciprocalRankFusion(60),
                new DefaultEvidenceBuilder(),
                traces::add,
                catalog,
                router,
                configurationStore,
                new RetrievalConfigurationResolver(),
                hardLimits,
                observationPublisher,
                new HmacSha256TextFingerprinter(
                        "test-only-retrieval-observation-key-0001",
                        "test-v1"
                ),
                Runnable::run,
                CLOCK,
                0.5D,
                TIMEOUT,
                TIMEOUT,
                TIMEOUT,
                TIMEOUT,
                TIMEOUT,
                TIMEOUT
        );
    }

    /** 构造完整且满足当前硬上限的 Space 配置。 */
    private RetrievalConfiguration configuration(
            boolean coverageEnabled,
            boolean rerankerEnabled,
            int maximumAttempts,
            Set<RetrievalConfiguration.ChainNode> enabledNodes
    ) {
        RetrievalConfiguration baseline = RetrievalConfiguration.deterministicBaseline();
        EnumMap<RetrievalConfiguration.ChainNode, Boolean> chain = new EnumMap<>(
                RetrievalConfiguration.ChainNode.class
        );
        for (RetrievalConfiguration.ChainNode node
                : RetrievalConfiguration.ChainNode.values()) {
            chain.put(node, enabledNodes.contains(node));
        }
        boolean crossSpace = enabledNodes.contains(
                RetrievalConfiguration.ChainNode.NEXT_SPACE
        );
        boolean generatedVariantNode = enabledNodes.stream().anyMatch(node ->
                node == RetrievalConfiguration.ChainNode.GAP_QUERY
                        || node == RetrievalConfiguration.ChainNode.PRF
                        || node == RetrievalConfiguration.ChainNode.STEP_BACK
                        || node == RetrievalConfiguration.ChainNode.HYDE
        );
        RetrievalConfiguration.Branches branches = generatedVariantNode
                ? new RetrievalConfiguration.Branches(
                        2,
                        baseline.branches().maximumRetrievalBranches(),
                        baseline.branches().rrfConstant(),
                        baseline.branches().channels()
                )
                : baseline.branches();
        return new RetrievalConfiguration(
                baseline.firstRound(),
                branches,
                new RetrievalConfiguration.Reranker(
                        rerankerEnabled,
                        "test",
                        rerankerEnabled ? "reranker-v1" : "rrf-order",
                        20,
                        10
                ),
                new RetrievalConfiguration.Coverage(
                        coverageEnabled,
                        "test",
                        coverageEnabled ? "coverage-v1" : "none",
                        "v1",
                        20,
                        0.75D
                ),
                maximumAttempts,
                chain,
                new RetrievalConfiguration.CrossSpace(
                        crossSpace,
                        crossSpace ? 2 : 1
                )
        );
    }

    private SpaceRetrievalConfiguration materialized(
            KnowledgeSpaceId spaceId,
            RetrievalConfiguration configuration
    ) {
        return SpaceRetrievalConfiguration.create(
                TENANT,
                spaceId,
                1L,
                configuration,
                new PrincipalId("admin"),
                CLOCK.instant()
        );
    }

    /** 只实现测试读取路径，避免把版本写入语义带入检索验收。 */
    private SpaceRetrievalConfigurationStore configurationStore(
            Map<KnowledgeSpaceId, SpaceRetrievalConfiguration> values
    ) {
        return new SpaceRetrievalConfigurationStore() {
            @Override
            public Optional<SpaceRetrievalConfiguration> findCurrent(
                    TenantId tenantId,
                    KnowledgeSpaceId spaceId
            ) {
                return TENANT.equals(tenantId)
                        ? Optional.ofNullable(values.get(spaceId))
                        : Optional.empty();
            }

            @Override
            public Optional<SpaceRetrievalConfiguration> findRevision(
                    TenantId tenantId,
                    KnowledgeSpaceId spaceId,
                    long revision
            ) {
                return findCurrent(tenantId, spaceId)
                        .filter(value -> value.revision() == revision);
            }

            @Override
            public List<SpaceRetrievalConfiguration> history(
                    TenantId tenantId,
                    KnowledgeSpaceId spaceId,
                    int limit
            ) {
                return limit < 1
                        ? List.of()
                        : findCurrent(tenantId, spaceId).stream().toList();
            }

            @Override
            public ActivationOutcome appendAndActivate(
                    SpaceRetrievalConfiguration configuration,
                    long expectedCurrentRevision
            ) {
                throw new UnsupportedOperationException("测试存储只读");
            }
        };
    }

    private RetrievalSpaceCatalog stableCatalog() {
        return (tenantId, allowedSpaceIds) -> allowedSpaceIds.stream()
                .map(spaceId -> new SpaceRoutingCandidate(
                        spaceId,
                        spaceId.value(),
                        ""
                ))
                .toList();
    }

    private ActiveRevisionGuard allowAllRevisions() {
        return new ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId tenantId,
                    DocumentId documentId,
                    UUID revisionId
            ) {
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

    private KnowledgeQuery query(
            Set<KnowledgeSpaceId> spaces,
            RetrievalConfigurationOverride override,
            List<EvidenceRequirement> requirements,
            int topK
    ) {
        return query(
                spaces,
                override,
                requirements,
                topK,
                Map.of(),
                RetrievalConstraintInput.empty()
        );
    }

    /** 构造携带显式硬过滤与可变约束的测试广场查询。 */
    private KnowledgeQuery query(
            Set<KnowledgeSpaceId> spaces,
            RetrievalConfigurationOverride override,
            List<EvidenceRequirement> requirements,
            int topK,
            Map<String, String> filters,
            RetrievalConstraintInput constraints
    ) {
        return new KnowledgeQuery(
                UUID.randomUUID(),
                new PrincipalContext(
                        TENANT,
                        new PrincipalId("user-1"),
                        Set.of("reader"),
                        Set.of("engineering"),
                        false
                ),
                QUERY_TEXT,
                spaces,
                topK,
                filters,
                constraints,
                "给出可执行的恢复办法",
                requirements,
                override,
                RetrievalObservationPurpose.TEST_PLAZA
        );
    }

    private Retriever retriever(
            java.util.function.Function<RetrievalRequest, List<RetrievalCandidate>> action
    ) {
        return new Retriever() {
            @Override
            public RetrievalComponentVersion componentVersion() {
                return new RetrievalComponentVersion(
                        "retriever-keyword",
                        "test",
                        "keyword",
                        "v1"
                );
            }

            @Override
            public RetrievalChannel channel() {
                return RetrievalChannel.KEYWORD;
            }

            @Override
            public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
                return action.apply(request);
            }
        };
    }

    private RetrievalCandidate candidate(
            KnowledgeSpaceId spaceId,
            UUID chunkId,
            int rank
    ) {
        return new RetrievalCandidate(
                chunkId,
                TENANT,
                spaceId,
                DocumentId.random(),
                UUID.randomUUID(),
                RetrievalChannel.KEYWORD,
                rank,
                0.80D,
                "订单服务故障手册",
                List.of("恢复"),
                CANDIDATE_CONTENT,
                "https://knowledge.example/order-timeout",
                Map.of("authority", "90")
        );
    }

    private List<UUID> evidenceChunkIds(EvidenceBundle result) {
        return result.evidences().stream()
                .map(value -> value.citation().chunkId())
                .toList();
    }
}
