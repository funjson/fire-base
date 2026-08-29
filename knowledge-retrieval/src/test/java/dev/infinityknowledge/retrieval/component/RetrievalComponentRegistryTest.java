package dev.infinityknowledge.retrieval.component;

import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.CoverageJudgeComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.FeedbackQueryPlannerComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.RerankerComponent;
import dev.infinityknowledge.retrieval.component.RetrievalComponentRegistry.TerminologyServiceComponent;
import dev.infinityknowledge.spi.retrieval.CoverageJudgmentResult;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanningRequest;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanningResult;
import dev.infinityknowledge.spi.retrieval.QueryVariant;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;
import dev.infinityknowledge.spi.retrieval.RerankResult;
import dev.infinityknowledge.spi.retrieval.RetrievalComponentVersion;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionRequest;
import dev.infinityknowledge.spi.retrieval.TerminologyExpansionResult;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 Space 配置只能选择已安装且与观测合同绑定的执行组件。 */
class RetrievalComponentRegistryTest {

    @Test
    void configuredRerankerSelectsAndExecutesTheBoundInstance() {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger selectedCalls = new AtomicInteger();
        RerankerComponent first = reranker("model-a", firstCalls);
        RerankerComponent selected = reranker("model-b", selectedCalls);
        RetrievalComponentRegistry registry = registry(
                List.of(),
                List.of(first, selected)
        );

        var resolved = registry.resolve(withReranker("vendor", "model-b"));
        resolved.reranker().implementation().rerank("query", List.of(), 1);

        assertEquals(0, firstCalls.get());
        assertEquals(1, selectedCalls.get());
        assertEquals("model-b", resolved.reranker().version().model());
    }

    @Test
    void unknownEnabledModelIsRejectedBeforeExecution() {
        RetrievalComponentRegistry registry = registry(
                List.of(),
                List.of(reranker("model-a", new AtomicInteger()))
        );

        assertThrows(
                RetrievalComponentUnavailableException.class,
                () -> registry.resolve(withReranker("vendor", "not-installed"))
        );
    }

    @Test
    void configuredCoverageProfileSelectsAndExecutesTheBoundJudge() {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger selectedCalls = new AtomicInteger();
        CoverageJudgeComponent first = coverage("model-a", "prompt-v1", firstCalls);
        CoverageJudgeComponent selected = coverage("model-b", "prompt-v2", selectedCalls);
        RetrievalComponentRegistry registry = new RetrievalComponentRegistry(
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(first, selected),
                List.of()
        );

        var resolved = registry.resolve(withCoverage("vendor", "model-b", "prompt-v2"));
        resolved.coverageJudge().implementation().judge(null);

        assertEquals(0, firstCalls.get());
        assertEquals(1, selectedCalls.get());
        assertEquals("prompt-v2", resolved.coverageJudge().version().version());
    }

    @Test
    void unknownCoveragePromptVersionIsRejectedBeforeExecution() {
        RetrievalComponentRegistry registry = new RetrievalComponentRegistry(
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(coverage("model-a", "prompt-v1", new AtomicInteger())),
                List.of()
        );

        assertThrows(
                RetrievalComponentUnavailableException.class,
                () -> registry.resolve(withCoverage("vendor", "model-a", "prompt-v2"))
        );
    }

    @Test
    void enabledFeedbackNodeExecutesTheInstalledPlannerContract() {
        AtomicInteger calls = new AtomicInteger();
        FeedbackQueryPlannerComponent planner = new FeedbackQueryPlannerComponent(
                new RetrievalComponentVersion(
                        "feedback-query-planner", "vendor", "planner-a", "prompt-v3"
                ),
                request -> {
                    calls.incrementAndGet();
                    return new FeedbackQueryPlanningResult(
                            new QueryVariant("gap-1", QueryVariantKind.GAP_QUERY, "query"),
                            "vendor",
                            "planner-a"
                    );
                }
        );
        RetrievalComponentRegistry registry = new RetrievalComponentRegistry(
                List.of(),
                Optional.of(planner),
                List.of(),
                List.of(coverage("coverage-a", "prompt-v1", new AtomicInteger())),
                List.of()
        );

        var resolved = registry.resolve(withFeedbackChain());
        resolved.feedbackQueryPlanner().implementation().plan(
                new FeedbackQueryPlanningRequest(
                        QueryVariantKind.GAP_QUERY,
                        "query",
                        "",
                        List.of(),
                        List.of()
                )
        );

        assertEquals(1, calls.get());
        assertEquals("planner-a", resolved.feedbackQueryPlanner().version().model());
        assertEquals("prompt-v3", resolved.feedbackQueryPlanner().version().version());
    }

    @Test
    void terminologyResourceSelectsTheBoundServiceAndChecksItsVersion() {
        AtomicInteger calls = new AtomicInteger();
        TerminologyServiceComponent terminology = new TerminologyServiceComponent(
                new RetrievalComponentVersion(
                        "terminology-service", "controlled", "medical-v1", "resource-v7"
                ),
                request -> {
                    calls.incrementAndGet();
                    return TerminologyExpansionResult.none("controlled", "resource-v7");
                }
        );
        RetrievalComponentRegistry registry = registry(
                List.of(terminology),
                List.of()
        );
        var resolved = registry.resolve(withTerminology("medical-v1"));

        resolved.terminologyService().implementation().expand(
                new TerminologyExpansionRequest("query", "medical-v1", 2)
        );

        assertEquals(1, calls.get());
        assertEquals("resource-v7", resolved.terminologyService().version().version());
    }

    @Test
    void terminologyResultCannotReportAnotherInstalledVersion() {
        TerminologyServiceComponent terminology = new TerminologyServiceComponent(
                new RetrievalComponentVersion(
                        "terminology-service", "controlled", "medical-v1", "resource-v7"
                ),
                request -> TerminologyExpansionResult.none("controlled", "resource-v8")
        );

        assertThrows(
                IllegalStateException.class,
                () -> terminology.implementation().expand(
                        new TerminologyExpansionRequest("query", "medical-v1", 2)
                )
        );
    }

    private RetrievalComponentRegistry registry(
            List<TerminologyServiceComponent> terminologyServices,
            List<RerankerComponent> rerankers
    ) {
        return new RetrievalComponentRegistry(
                terminologyServices,
                Optional.empty(),
                rerankers,
                List.of(),
                List.of()
        );
    }

    private RerankerComponent reranker(String model, AtomicInteger calls) {
        return new RerankerComponent(
                new RetrievalComponentVersion("reranker", "vendor", model, "template-v1"),
                (query, candidates, limit) -> {
                    calls.incrementAndGet();
                    return new RerankResult(List.of(), java.util.Map.of(), 0, "TEST");
                }
        );
    }

    private CoverageJudgeComponent coverage(
            String model,
            String promptVersion,
            AtomicInteger calls
    ) {
        return new CoverageJudgeComponent(
                new RetrievalComponentVersion(
                        "coverage-judge", "vendor", model, promptVersion
                ),
                request -> {
                    calls.incrementAndGet();
                    return new CoverageJudgmentResult(0.8D, List.of(), List.of(), "TEST");
                }
        );
    }

    private RetrievalConfiguration withReranker(String provider, String model) {
        RetrievalConfiguration baseline = RetrievalConfiguration.deterministicBaseline();
        return new RetrievalConfiguration(
                baseline.firstRound(),
                baseline.branches(),
                new RetrievalConfiguration.Reranker(true, provider, model, 40, 8),
                baseline.coverage(),
                baseline.maximumRetrievalAttempts(),
                baseline.chainNodeEnables(),
                baseline.crossSpace()
        );
    }

    private RetrievalConfiguration withTerminology(String resourceId) {
        RetrievalConfiguration baseline = RetrievalConfiguration.deterministicBaseline();
        return new RetrievalConfiguration(
                new RetrievalConfiguration.FirstRound(true, resourceId, 2),
                new RetrievalConfiguration.Branches(
                        2,
                        baseline.branches().maximumRetrievalBranches(),
                        baseline.branches().rrfConstant(),
                        baseline.branches().channels()
                ),
                baseline.reranker(),
                baseline.coverage(),
                baseline.maximumRetrievalAttempts(),
                baseline.chainNodeEnables(),
                baseline.crossSpace()
        );
    }

    private RetrievalConfiguration withCoverage(
            String provider,
            String model,
            String promptVersion
    ) {
        RetrievalConfiguration baseline = RetrievalConfiguration.deterministicBaseline();
        return new RetrievalConfiguration(
                baseline.firstRound(),
                baseline.branches(),
                baseline.reranker(),
                new RetrievalConfiguration.Coverage(
                        true,
                        provider,
                        model,
                        promptVersion,
                        20,
                        0.75D
                ),
                baseline.maximumRetrievalAttempts(),
                baseline.chainNodeEnables(),
                baseline.crossSpace()
        );
    }

    private RetrievalConfiguration withFeedbackChain() {
        RetrievalConfiguration baseline = RetrievalConfiguration.deterministicBaseline();
        EnumMap<RetrievalConfiguration.ChainNode, Boolean> nodes =
                new EnumMap<>(baseline.chainNodeEnables());
        nodes.put(RetrievalConfiguration.ChainNode.GAP_QUERY, true);
        return new RetrievalConfiguration(
                baseline.firstRound(),
                baseline.branches(),
                baseline.reranker(),
                new RetrievalConfiguration.Coverage(
                        true,
                        "vendor",
                        "coverage-a",
                        "prompt-v1",
                        20,
                        0.75D
                ),
                2,
                nodes,
                baseline.crossSpace()
        );
    }
}
