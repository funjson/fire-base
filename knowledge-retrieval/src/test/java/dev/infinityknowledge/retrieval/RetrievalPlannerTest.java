package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.retrieval.query.PlannedQuery;
import dev.infinityknowledge.retrieval.query.QueryOptimizationPlan;
import dev.infinityknowledge.retrieval.query.ResolvedConstraints;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定逐 Variant Retriever Assignment 与物理编译规则。 */
class RetrievalPlannerTest {

    /** HyDE 即使面对 Hybrid 原计划，也只能进入向量通道。 */
    @Test
    void assignsHydeOnlyToVectorChannel() {
        TestInput input = input("订单服务 ERR-1001 如何恢复");
        RetrievalPlanner planner = new RetrievalPlanner(RetrieverAssignmentMode.RULE_ONLY);

        List<RetrievalBranchRequest> requests = planner.plan(
                input.query(),
                input.originalPlan(),
                QueryOptimizationPlan.feedback(
                        ResolvedConstraints.empty(),
                        new PlannedQuery(
                                "hyde-1",
                                QueryVariantKind.HYDE,
                                "一段用于向量定位的假想故障恢复说明",
                                "test",
                                "hyde-v1"
                        )
                ),
                configuration(1, 4),
                input.originalPlan().channels(),
                input.scope(),
                Instant.now().plusSeconds(1),
                new java.util.ArrayList<>()
        );

        assertEquals(1, requests.size());
        assertEquals(
                Set.of(RetrievalChannel.VECTOR),
                requests.getFirst().request().plan().channels()
        );
    }

    /** Q0 与术语 Q1 在整轮两分支预算下应形成互补通道，而不是共享全局通道。 */
    @Test
    void assignsFirstRoundVariantsComplementaryChannels() {
        TestInput input = input("订单服务为什么无法自动恢复");
        RetrievalPlanner planner = new RetrievalPlanner(RetrieverAssignmentMode.RULE_ONLY);

        List<RetrievalBranchRequest> requests = planner.plan(
                input.query(),
                input.originalPlan(),
                QueryOptimizationPlan.firstRound(
                        ResolvedConstraints.empty(),
                        List.of(
                                PlannedQuery.original(input.query().text()),
                                new PlannedQuery(
                                        "q1",
                                        QueryVariantKind.TERM_EXPANSION,
                                        "订单服务 order-service 为什么无法自动恢复",
                                        "controlled-dictionary",
                                        "engineering-v7"
                                )
                        )
                ),
                configuration(2, 2),
                input.originalPlan().channels(),
                input.scope(),
                Instant.now().plusSeconds(1),
                new java.util.ArrayList<>()
        );

        assertEquals(2, requests.size());
        assertEquals("q0:vector", requests.get(0).branchId());
        assertEquals("q1:keyword", requests.get(1).branchId());
    }

    /** 精确编号使 Q0 偏关键词，Q1 则在整轮分配中补充语义召回。 */
    @Test
    void usesWholePlanWhenExactQ0AndTermExpansionCoexist() {
        TestInput input = input("订单服务 ERR-1001 如何恢复");
        RetrievalPlanner planner = new RetrievalPlanner(RetrieverAssignmentMode.RULE_ONLY);

        List<RetrievalBranchRequest> requests = planner.plan(
                input.query(),
                input.originalPlan(),
                QueryOptimizationPlan.firstRound(
                        ResolvedConstraints.empty(),
                        List.of(
                                PlannedQuery.original(input.query().text()),
                                new PlannedQuery(
                                        "q1",
                                        QueryVariantKind.TERM_EXPANSION,
                                        "订单服务 order-service ERR-1001 如何恢复",
                                        "controlled-dictionary",
                                        "engineering-v7"
                                )
                        )
                ),
                configuration(2, 2),
                input.originalPlan().channels(),
                input.scope(),
                Instant.now().plusSeconds(1),
                new java.util.ArrayList<>()
        );

        assertEquals("q0:keyword", requests.get(0).branchId());
        assertEquals("q1:vector", requests.get(1).branchId());
    }

    /** 未安装的可选通道必须在编译阶段剔除并留下稳定降级码。 */
    @Test
    void filtersUnavailableRetrieverBeforePhysicalBranchCompilation() {
        TestInput input = input("订单服务依赖哪些组件");
        RetrievalPlanner planner = new RetrievalPlanner(RetrieverAssignmentMode.RULE_ONLY);
        QueryPlan analyzed = new QueryPlan(
                input.query().text(),
                input.query().text(),
                Set.of(
                        RetrievalChannel.KEYWORD,
                        RetrievalChannel.VECTOR,
                        RetrievalChannel.GRAPH
                ),
                20
        );
        RetrievalConfiguration configuration = configurationWithGraphEnabled();
        List<String> warnings = new java.util.ArrayList<>();

        List<RetrievalBranchRequest> requests = planner.plan(
                input.query(),
                analyzed,
                QueryOptimizationPlan.firstRound(
                        ResolvedConstraints.empty(),
                        List.of(PlannedQuery.original(input.query().text()))
                ),
                configuration,
                Set.of(RetrievalChannel.KEYWORD, RetrievalChannel.VECTOR),
                input.scope(),
                Instant.now().plusSeconds(1),
                warnings
        );

        assertEquals(
                List.of("q0:keyword", "q0:vector"),
                requests.stream().map(RetrievalBranchRequest::branchId).toList()
        );
        assertEquals(List.of("RETRIEVER_GRAPH_UNAVAILABLE"), warnings);
    }

    /** 首轮和反馈轮的 Q0 执行规则由计划类型直接约束。 */
    @Test
    void rejectsOriginalQueryInsideFeedbackPlan() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QueryOptimizationPlan.feedback(
                        ResolvedConstraints.empty(),
                        PlannedQuery.original("原查询")
                )
        );
    }

    /** 未提供模型分类器时不得伪装成 RULE_WITH_LLM 已经生效。 */
    @Test
    void rejectsRuleWithLlmWithoutClassifierAdapter() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetrievalPlanner(RetrieverAssignmentMode.RULE_WITH_LLM)
        );
    }

    private RetrievalConfiguration configuration(int maximumVariants, int maximumBranches) {
        EnumMap<RetrievalChannel, RetrievalConfiguration.Branch> channels =
                new EnumMap<>(RetrievalChannel.class);
        channels.put(
                RetrievalChannel.KEYWORD,
                new RetrievalConfiguration.Branch(true, 20, 1.0D)
        );
        channels.put(
                RetrievalChannel.VECTOR,
                new RetrievalConfiguration.Branch(true, 20, 1.0D)
        );
        channels.put(
                RetrievalChannel.GRAPH,
                new RetrievalConfiguration.Branch(false, 20, 0.8D)
        );
        channels.put(
                RetrievalChannel.PAGE,
                new RetrievalConfiguration.Branch(false, 20, 0.8D)
        );
        EnumMap<RetrievalConfiguration.ChainNode, Boolean> chain =
                new EnumMap<>(RetrievalConfiguration.ChainNode.class);
        for (RetrievalConfiguration.ChainNode node : RetrievalConfiguration.ChainNode.values()) {
            chain.put(node, false);
        }
        return new RetrievalConfiguration(
                new RetrievalConfiguration.FirstRound(maximumVariants > 1, "test-terms", 2),
                new RetrievalConfiguration.Branches(
                        maximumVariants,
                        maximumBranches,
                        60,
                        channels
                ),
                new RetrievalConfiguration.Reranker(
                        false, "deterministic", "rrf-order", 20, 5
                ),
                new RetrievalConfiguration.Coverage(
                        false, "deterministic", "none", "none", 5, 0.8D
                ),
                1,
                chain,
                new RetrievalConfiguration.CrossSpace(false, 1)
        );
    }

    private RetrievalConfiguration configurationWithGraphEnabled() {
        RetrievalConfiguration source = configuration(1, 4);
        EnumMap<RetrievalChannel, RetrievalConfiguration.Branch> channels =
                new EnumMap<>(source.branches().channels());
        channels.put(
                RetrievalChannel.GRAPH,
                new RetrievalConfiguration.Branch(true, 20, 0.8D)
        );
        return new RetrievalConfiguration(
                source.firstRound(),
                new RetrievalConfiguration.Branches(
                        source.branches().maximumVariantsPerAttempt(),
                        source.branches().maximumRetrievalBranches(),
                        source.branches().rrfConstant(),
                        channels
                ),
                source.reranker(),
                source.coverage(),
                source.maximumRetrievalAttempts(),
                source.chainNodeEnables(),
                source.crossSpace()
        );
    }

    private TestInput input(String text) {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        KnowledgeQuery query = KnowledgeQuery.online(
                UUID.randomUUID(),
                new PrincipalContext(
                        tenantId,
                        new PrincipalId("user-a"),
                        Set.of("reader"),
                        Set.of("engineering"),
                        false
                ),
                text,
                Set.of(spaceId),
                5,
                Map.of()
        );
        return new TestInput(
                query,
                new QueryPlan(
                        query.text(),
                        query.text(),
                        Set.of(RetrievalChannel.KEYWORD, RetrievalChannel.VECTOR),
                        20
                ),
                AccessScope.all(tenantId, Set.of(spaceId))
        );
    }

    private record TestInput(
            KnowledgeQuery query,
            QueryPlan originalPlan,
            AccessScope scope
    ) {
    }
}
