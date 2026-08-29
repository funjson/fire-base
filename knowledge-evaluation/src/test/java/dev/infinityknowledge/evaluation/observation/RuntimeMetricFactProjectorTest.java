package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证强类型事件只投影载荷真实提供的在线事实，不把缺失值伪造成零。 */
class RuntimeMetricFactProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("space-a");
    private static final String CONFIG = "a".repeat(64);
    private static final RetrievalObservationPayload.ComponentVersion COMPONENT =
            new RetrievalObservationPayload.ComponentVersion(
                    "COVERAGE_JUDGE", "provider-a", "model-a", "v1"
            );

    private final RuntimeMetricFactProjector projector = new RuntimeMetricFactProjector();

    @Test
    void projectsBranchTechnicalSuccessSeparatelyFromEmptyResult() {
        var payload = new RetrievalObservationPayload.RetrievalBranchCompleted(
                "branch-1",
                "BASELINE",
                "original",
                fingerprint(),
                RetrievalChannel.VECTOR,
                COMPONENT,
                "index-v1",
                8,
                8,
                List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(
                payload, RetrievalObservationStatus.SUCCEEDED, "NONE", 0, Set.of(SPACE)
        ));

        assertEquals(1.0D, value(facts, "retrieval.branch.empty"));
        assertEquals(1.0D, value(facts, "retrieval.branch.success"));
    }

    @Test
    void doesNotClassifyFailedBranchAsNormalEmptyResult() {
        var payload = new RetrievalObservationPayload.RetrievalBranchCompleted(
                "branch-1",
                "BASELINE",
                "original",
                fingerprint(),
                RetrievalChannel.VECTOR,
                COMPONENT,
                "index-v1",
                8,
                8,
                List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(
                payload,
                RetrievalObservationStatus.TIMED_OUT,
                "RETRIEVER_TIMEOUT",
                0,
                Set.of(SPACE)
        ));

        assertFalse(has(facts, "retrieval.branch.empty"));
        assertEquals(0.0D, value(facts, "retrieval.branch.success"));
    }

    @Test
    void projectsFusionDuplicateRateAsAggregatableNumeratorAndDenominator() {
        var payload = new RetrievalObservationPayload.FusionCompleted(
                "RRF", COMPONENT, 6, 4, 60, List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(payload));
        List<MetricFact.Runtime> ratio = facts.stream()
                .filter(fact -> "retrieval.fusion.duplicate.rate".equals(fact.metricKey()))
                .toList();

        assertEquals(2, ratio.size());
        assertEquals(2.0D, ratio.stream()
                .filter(fact -> fact.aggregation() == MetricFact.Aggregation.RATIO_NUMERATOR)
                .findFirst().orElseThrow().value());
        assertEquals(6.0D, ratio.stream()
                .filter(fact -> fact.aggregation() == MetricFact.Aggregation.RATIO_DENOMINATOR)
                .findFirst().orElseThrow().value());
        assertEquals(6.0D, value(facts, "retrieval.fusion.input_candidate.count"));
        assertEquals(4.0D, value(facts, "retrieval.fusion.unique_candidate.count"));
    }

    @Test
    void omitsFusionRateWithoutDenominator() {
        var payload = new RetrievalObservationPayload.FusionCompleted(
                "RRF", COMPONENT, 0, 0, 60, List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(payload));

        assertFalse(has(facts, "retrieval.fusion.duplicate.rate"));
    }

    @Test
    void projectsCoverageScoreAndOnlyAvailableTokenCounts() {
        var measured = new RetrievalObservationPayload.CoverageCheckCompleted(
                COMPONENT,
                8,
                5,
                2,
                RetrievalObservationPayload.OptionalCount.of(2),
                RetrievalObservationPayload.OptionalScore.of(0.8D),
                RetrievalObservationPayload.OptionalScore.of(0.7D),
                RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT,
                "THRESHOLD_REACHED",
                new RetrievalObservationPayload.UsageCount(2, true, 11, 7)
        );
        var unmeasured = new RetrievalObservationPayload.CoverageCheckCompleted(
                COMPONENT,
                8,
                4,
                2,
                RetrievalObservationPayload.OptionalCount.absent(),
                RetrievalObservationPayload.OptionalScore.absent(),
                RetrievalObservationPayload.OptionalScore.absent(),
                RetrievalObservationPayload.CoverageTerminalStatus.CHECK_FAILED,
                "PROVIDER_FAILED",
                RetrievalObservationPayload.UsageCount.unmeasured(1)
        );

        List<MetricFact.Runtime> measuredFacts = projector.project(observation(measured));
        List<MetricFact.Runtime> unmeasuredFacts = projector.project(observation(unmeasured));

        assertEquals(0.8D, value(measuredFacts, "retrieval.coverage.score"));
        assertFalse(has(measuredFacts, "retrieval.coverage.sufficient"));
        assertEquals(5.0D, value(measuredFacts, "retrieval.coverage.retained_candidate.count"));
        assertEquals(2.0D, value(measuredFacts, "retrieval.coverage.model_request.count"));
        assertEquals(11.0D, value(measuredFacts, "retrieval.coverage.model_input_token.count"));
        assertEquals(7.0D, value(measuredFacts, "retrieval.coverage.model_output_token.count"));
        assertFalse(has(unmeasuredFacts, "retrieval.coverage.score"));
        assertFalse(has(unmeasuredFacts, "retrieval.coverage.sufficient"));
        assertFalse(has(unmeasuredFacts, "retrieval.coverage.model_input_token.count"));
        assertFalse(has(unmeasuredFacts, "retrieval.coverage.model_output_token.count"));
    }

    @Test
    void projectsChainDeltaSpaceChangeAndTerminalAttemptCount() {
        var node = new RetrievalObservationPayload.ChainNodeCompleted(
                "QUERY_EXPANSION",
                "EXPAND",
                "APPLIED",
                3,
                5,
                RetrievalObservationPayload.OptionalScore.of(0.35D),
                RetrievalObservationPayload.OptionalScore.of(0.6D),
                RetrievalObservationPayload.UsageCount.none()
        );
        var changed = new RetrievalObservationPayload.SpaceChanged(
                SPACE,
                new KnowledgeSpaceId("space-b"),
                "NEXT_SPACE"
        );
        var terminal = new RetrievalObservationPayload.ExecutionTerminal(
                RetrievalTerminalStatus.INSUFFICIENT,
                RetrievalStopReason.OPTIMIZATION_CHAIN_EXHAUSTED,
                3,
                4,
                true,
                List.of("RERANK_FALLBACK")
        );

        List<MetricFact.Runtime> nodeFacts = projector.project(observation(node));
        List<MetricFact.Runtime> changedFacts = projector.project(observation(
                changed,
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                0,
                Set.of(SPACE, new KnowledgeSpaceId("space-b"))
        ));
        List<MetricFact.Runtime> terminalFacts = projector.project(observation(
                terminal,
                RetrievalObservationStatus.DEGRADED,
                "RERANK_FALLBACK",
                2,
                Set.of(SPACE)
        ));

        assertEquals(1.0D, value(nodeFacts, "retrieval.chain.node.executed.count"));
        assertEquals(0.25D, value(nodeFacts, "retrieval.chain.node.coverage.delta"));
        assertEquals(1.0D, value(changedFacts, "retrieval.space.changed.count"));
        assertEquals(3.0D, value(terminalFacts, "retrieval.request.attempt.count"));
        assertEquals(4.0D, value(terminalFacts, "retrieval.request.result.count"));
        assertEquals(1.0D, value(terminalFacts, "retrieval.request.degraded"));
        assertEquals(1.0D, value(terminalFacts, "retrieval.request.technical_success"));
    }

    @Test
    void usesBusinessTerminalStatusForTechnicalSuccessAndStatusDimension() {
        var terminal = new RetrievalObservationPayload.ExecutionTerminal(
                RetrievalTerminalStatus.CHECK_FAILED,
                RetrievalStopReason.COVERAGE_CHECK_FAILED,
                1,
                0,
                false,
                List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(
                terminal,
                RetrievalObservationStatus.FAILED,
                "COVERAGE_CHECK_FAILED",
                0,
                Set.of(SPACE)
        ));
        MetricFact.Runtime success = facts.stream()
                .filter(fact -> "retrieval.request.technical_success".equals(fact.metricKey()))
                .findFirst()
                .orElseThrow();

        assertEquals(0.0D, success.value());
        assertEquals("CHECK_FAILED", success.dimensions().require(MetricDimensions.Key.STATUS));
        assertEquals(
                "COVERAGE_CHECK_FAILED",
                success.dimensions().require(MetricDimensions.Key.STOP_REASON)
        );
        assertEquals(3, success.metricDefinitionVersion());
    }

    @Test
    void projectsRerankExecutionAndFallbackFlags() {
        var payload = new RetrievalObservationPayload.RerankCompleted(
                "MODEL",
                COMPONENT,
                true,
                true,
                5,
                List.of(),
                RetrievalObservationPayload.UsageCount.unmeasured(1)
        );

        List<MetricFact.Runtime> facts = projector.project(observation(
                payload, RetrievalObservationStatus.DEGRADED, "MODEL_FAILED", 0, Set.of(SPACE)
        ));

        assertEquals(1.0D, value(facts, "retrieval.rerank.executed"));
        assertEquals(1.0D, value(facts, "retrieval.rerank.fallback"));
        assertEquals(1.0D, value(facts, "retrieval.rerank.model_request.count"));
        assertFalse(has(facts, "retrieval.rerank.model_input_token.count"));
        assertFalse(has(facts, "retrieval.rerank.model_output_token.count"));
    }

    @Test
    void projectsQueryPlanningUsageWithoutQueryText() {
        var payload = new RetrievalObservationPayload.QueryPlanningCompleted(
                COMPONENT,
                List.of(),
                new RetrievalObservationPayload.UsageCount(1, true, 9, 3)
        );

        List<MetricFact.Runtime> facts = projector.project(observation(payload));

        assertEquals(1.0D, value(facts, "retrieval.query_planning.model_request.count"));
        assertEquals(9.0D, value(facts, "retrieval.query_planning.model_input_token.count"));
        assertEquals(3.0D, value(facts, "retrieval.query_planning.model_output_token.count"));
    }

    private static RetrievalObservation observation(RetrievalObservationPayload payload) {
        return observation(
                payload, RetrievalObservationStatus.SUCCEEDED, "NONE", 0, Set.of(SPACE)
        );
    }

    private static RetrievalObservation observation(
            RetrievalObservationPayload payload,
            RetrievalObservationStatus status,
            String reasonCode,
            int attemptIndex,
            Set<KnowledgeSpaceId> spaces
    ) {
        return new RetrievalObservation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                RetrievalObservationPurpose.ONLINE,
                TENANT,
                spaces,
                0,
                attemptIndex,
                payload.stage(),
                status,
                reasonCode,
                CONFIG,
                NOW.minusMillis(10),
                NOW,
                RetrievalObservation.CURRENT_SCHEMA_VERSION,
                payload
        );
    }

    private static RetrievalObservationPayload.TextFingerprint fingerprint() {
        return new RetrievalObservationPayload.TextFingerprint(
                "HMAC_SHA256", "key-v1", "b".repeat(64)
        );
    }

    private static boolean has(List<MetricFact.Runtime> facts, String key) {
        return facts.stream().anyMatch(fact -> key.equals(fact.metricKey()));
    }

    private static double value(List<MetricFact.Runtime> facts, String key) {
        return facts.stream()
                .filter(fact -> key.equals(fact.metricKey()))
                .findFirst()
                .orElseThrow()
                .value();
    }
}
