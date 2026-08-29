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

/** 验证运行事实只使用强类型载荷，并保留每个比例的真实分子和分母。 */
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
    void projectsBranchRatesCandidateCountAndLowCardinalityDimensions() {
        var payload = new RetrievalObservationPayload.RetrievalBranchCompleted(
                "branch-1", "BASELINE", "original", fingerprint(),
                RetrievalChannel.VECTOR, COMPONENT, "index-v1",
                8, 8, List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(
                payload, RetrievalObservationStatus.SUCCEEDED, "NONE", 0, Set.of(SPACE)
        ));

        assertRatio(facts, "retrieval.branch.success.rate", 1.0D, 1.0D);
        assertRatio(facts, "retrieval.branch.empty.rate", 1.0D, 1.0D);
        assertEquals(0.0D, distribution(facts, "retrieval.branch.candidate.count"));
        MetricDimensions dimensions = facts.getFirst().dimensions();
        assertEquals("RETRIEVAL_BRANCH", dimensions.require(MetricDimensions.Key.STAGE));
        assertEquals("VECTOR", dimensions.require(MetricDimensions.Key.CHANNEL));
        assertEquals("0", dimensions.require(MetricDimensions.Key.VISIT_INDEX));
        assertEquals("SUCCEEDED", dimensions.require(MetricDimensions.Key.TECHNICAL_STATUS));
    }

    @Test
    void failedBranchDoesNotCreateAnEmptyResultDenominator() {
        var payload = new RetrievalObservationPayload.RetrievalBranchCompleted(
                "branch-1", "BASELINE", "original", fingerprint(),
                RetrievalChannel.VECTOR, COMPONENT, "index-v1",
                8, 8, List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(
                payload, RetrievalObservationStatus.TIMED_OUT,
                "RETRIEVER_TIMEOUT", 0, Set.of(SPACE)
        ));

        assertRatio(facts, "retrieval.branch.success.rate", 0.0D, 1.0D);
        assertFalse(has(facts, "retrieval.branch.empty.rate"));
        assertRatio(facts, "retrieval.stage.normal_completion.rate", 0.0D, 1.0D);
    }

    @Test
    void projectsFusionDuplicateRateAsAggregatableCounts() {
        var payload = new RetrievalObservationPayload.FusionCompleted(
                "RRF", COMPONENT, 6, 4, 60, List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(payload));

        assertRatio(facts, "retrieval.fusion.duplicate.rate", 2.0D, 6.0D);
        assertEquals(6.0D,
                distribution(facts, "retrieval.fusion.input_candidate.count"));
        assertEquals(4.0D,
                distribution(facts, "retrieval.fusion.unique_candidate.count"));
    }

    @Test
    void omitsFusionRateWithoutADenominator() {
        var payload = new RetrievalObservationPayload.FusionCompleted(
                "RRF", COMPONENT, 0, 0, 60, List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(payload));

        assertFalse(has(facts, "retrieval.fusion.duplicate.rate"));
    }

    @Test
    void projectsCoverageStatusAndOnlyAvailableModelUsage() {
        var measured = new RetrievalObservationPayload.CoverageCheckCompleted(
                COMPONENT, 8, 5, 2,
                RetrievalObservationPayload.OptionalCount.of(2),
                RetrievalObservationPayload.OptionalScore.of(0.8D),
                RetrievalObservationPayload.OptionalScore.of(0.7D),
                RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT,
                "THRESHOLD_REACHED",
                new RetrievalObservationPayload.UsageCount(2, true, 11, 7)
        );
        var unmeasured = new RetrievalObservationPayload.CoverageCheckCompleted(
                COMPONENT, 8, 4, 2,
                RetrievalObservationPayload.OptionalCount.absent(),
                RetrievalObservationPayload.OptionalScore.absent(),
                RetrievalObservationPayload.OptionalScore.absent(),
                RetrievalObservationPayload.CoverageTerminalStatus.CHECK_FAILED,
                "PROVIDER_FAILED",
                RetrievalObservationPayload.UsageCount.unmeasured(1)
        );
        var continuing = new RetrievalObservationPayload.CoverageCheckCompleted(
                COMPONENT, 8, 4, 2,
                RetrievalObservationPayload.OptionalCount.of(1),
                RetrievalObservationPayload.OptionalScore.of(0.5D),
                RetrievalObservationPayload.OptionalScore.of(0.7D),
                RetrievalObservationPayload.CoverageTerminalStatus.CONTINUE,
                "BELOW_THRESHOLD",
                RetrievalObservationPayload.UsageCount.none()
        );

        List<MetricFact.Runtime> measuredFacts = projector.project(observation(measured));
        List<MetricFact.Runtime> unmeasuredFacts = projector.project(observation(unmeasured));
        List<MetricFact.Runtime> continuingFacts = projector.project(observation(continuing));

        assertEquals(0.8D, distribution(measuredFacts, "retrieval.coverage.score"));
        assertEquals(5.0D, distribution(
                measuredFacts, "retrieval.coverage.retained_candidate.count"));
        assertRatio(measuredFacts,
                "retrieval.coverage.requirement_covered.rate", 2.0D, 2.0D);
        assertRatio(measuredFacts,
                "retrieval.coverage.candidate_retained.rate", 5.0D, 8.0D);
        assertRatio(measuredFacts,
                "retrieval.coverage.sufficient.rate", 1.0D, 1.0D);
        assertEquals("SUFFICIENT", measuredFacts.getFirst().dimensions()
                .require(MetricDimensions.Key.COVERAGE_STATUS));
        assertEquals(2.0D, sum(measuredFacts, "retrieval.coverage.model_request.count"));
        assertEquals(11.0D, sum(
                measuredFacts, "retrieval.coverage.model_input_token.count"));
        assertFalse(has(unmeasuredFacts, "retrieval.coverage.score"));
        assertFalse(has(unmeasuredFacts, "retrieval.coverage.sufficient.rate"));
        assertFalse(has(unmeasuredFacts, "retrieval.coverage.model_input_token.count"));
        assertRatio(continuingFacts,
                "retrieval.coverage.sufficient.rate", 0.0D, 1.0D);
    }

    @Test
    void projectsChainNodeStrategyDeltaAndUsage() {
        var node = new RetrievalObservationPayload.ChainNodeCompleted(
                "QUERY_EXPANSION", "EXPAND", "APPLIED", 3, 5,
                RetrievalObservationPayload.OptionalScore.of(0.35D),
                RetrievalObservationPayload.OptionalScore.of(0.6D),
                new RetrievalObservationPayload.UsageCount(1, true, 9, 3)
        );
        var nodeWithoutScores = new RetrievalObservationPayload.ChainNodeCompleted(
                "NEXT_SPACE", "SPACE_SWITCH", "APPLIED", 3, 3,
                RetrievalObservationPayload.OptionalScore.absent(),
                RetrievalObservationPayload.OptionalScore.absent(),
                RetrievalObservationPayload.UsageCount.none()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(node));
        List<MetricFact.Runtime> factsWithoutScores = projector.project(
                observation(nodeWithoutScores)
        );

        assertEquals(1.0D, count(facts, "retrieval.chain.node.executed.count"));
        assertEquals(0.25D,
                distribution(facts, "retrieval.chain.node.coverage.delta"));
        assertRatio(facts,
                "retrieval.chain.node.positive_coverage_gain.rate", 1.0D, 1.0D);
        assertEquals("QUERY_EXPANSION", facts.getFirst().dimensions()
                .require(MetricDimensions.Key.CHAIN_NODE));
        assertEquals("EXPAND", facts.getFirst().dimensions()
                .require(MetricDimensions.Key.STRATEGY));
        assertEquals(9.0D,
                sum(facts, "retrieval.chain.node.model_input_token.count"));
        assertFalse(has(factsWithoutScores,
                "retrieval.chain.node.positive_coverage_gain.rate"));
    }

    @Test
    void projectsRerankRatesInputOutputAndUnavailableUsageSafely() {
        var payload = new RetrievalObservationPayload.RerankCompleted(
                "MODEL", COMPONENT, true, true, 5, List.of(),
                RetrievalObservationPayload.UsageCount.unmeasured(1)
        );

        List<MetricFact.Runtime> facts = projector.project(observation(
                payload, RetrievalObservationStatus.DEGRADED,
                "MODEL_FAILED", 0, Set.of(SPACE)
        ));

        assertRatio(facts, "retrieval.rerank.executed.rate", 1.0D, 1.0D);
        assertRatio(facts, "retrieval.rerank.fallback.rate", 1.0D, 1.0D);
        assertEquals(5.0D,
                distribution(facts, "retrieval.rerank.input_candidate.count"));
        assertEquals(0.0D,
                distribution(facts, "retrieval.rerank.output_candidate.count"));
        assertEquals(1.0D, sum(facts, "retrieval.rerank.model_request.count"));
        assertFalse(has(facts, "retrieval.rerank.model_input_token.count"));
    }

    @Test
    void executionSnapshotProvidesTerminalAndOptimizationRatioFacts() {
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        List<RetrievalObservation> events = List.of(
                started(executionId, requestId, 0L),
                configured(executionId, requestId, 1L),
                coverage(executionId, requestId, 2L,
                        RetrievalObservationPayload.CoverageTerminalStatus.CONTINUE),
                coverage(executionId, requestId, 3L,
                        RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT),
                terminal(executionId, requestId, 4L,
                        RetrievalTerminalStatus.SUFFICIENT,
                        RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED)
        );
        var execution = new RetrievalExecutionObservation(
                executionId, requestId, RetrievalObservationPurpose.ONLINE, TENANT,
                List.of(new VisitedRetrievalConfiguration(0, SPACE, 1L, CONFIG)),
                events, ObservationCompleteness.COMPLETE, Set.of(), List.of()
        );

        List<MetricFact.Runtime> facts = projector.projectExecution(execution);

        assertEquals(1.0D, count(facts, "retrieval.request.count"));
        assertRatio(facts, "retrieval.request.terminal_observed.rate", 1.0D, 1.0D);
        assertRatio(facts, "retrieval.observation.complete.rate", 1.0D, 1.0D);
        assertRatio(facts, "retrieval.request.technical_success.rate", 1.0D, 1.0D);
        assertRatio(facts, "retrieval.request.degraded.rate", 0.0D, 1.0D);
        assertRatio(facts, "retrieval.coverage.first_sufficient.rate", 0.0D, 1.0D);
        assertRatio(facts, "retrieval.coverage.final_sufficient.rate", 1.0D, 1.0D);
        assertRatio(facts, "retrieval.coverage.recovery.rate", 1.0D, 1.0D);
        assertRatio(facts,
                "retrieval.optimization.budget_exhausted.rate", 0.0D, 1.0D);
        assertEquals(40.0D, distribution(facts, "retrieval.request.duration.ms"));
        MetricDimensions dimensions = facts.getFirst().dimensions();
        assertEquals("SUCCEEDED",
                dimensions.require(MetricDimensions.Key.TECHNICAL_STATUS));
        assertEquals("SUFFICIENT",
                dimensions.require(MetricDimensions.Key.TERMINAL_STATUS));
        assertEquals(RuntimeMetricFactProjector.METRIC_DEFINITION_VERSION,
                facts.getFirst().metricDefinitionVersion());
    }

    @Test
    void incompleteExecutionKeepsDenominatorsButDoesNotInventTerminalFacts() {
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        RetrievalObservation started = started(executionId, requestId, 0L);
        var execution = new RetrievalExecutionObservation(
                executionId, requestId, RetrievalObservationPurpose.ONLINE, TENANT,
                List.of(), List.of(started), ObservationCompleteness.INCOMPLETE,
                Set.of(ObservationIncompleteReason.TERMINAL_MISSING), List.of()
        );

        List<MetricFact.Runtime> facts = projector.projectExecution(execution);

        assertRatio(facts, "retrieval.request.terminal_observed.rate", 0.0D, 1.0D);
        assertRatio(facts, "retrieval.observation.complete.rate", 0.0D, 1.0D);
        assertFalse(has(facts, "retrieval.request.technical_success.rate"));
        assertFalse(has(facts, "retrieval.request.duration.ms"));
        assertFalse(has(facts, "retrieval.coverage.final_sufficient.rate"));
        assertEquals(MetricDimensions.UNOBSERVED_TECHNICAL_STATUS,
                facts.getFirst().dimensions()
                        .require(MetricDimensions.Key.TECHNICAL_STATUS));
    }

    @Test
    void terminalEventKeepsTechnicalAndBusinessStatusesSeparate() {
        var terminal = new RetrievalObservationPayload.ExecutionTerminal(
                RetrievalTerminalStatus.CHECK_FAILED,
                RetrievalStopReason.COVERAGE_CHECK_FAILED,
                1, 0, false, List.of()
        );

        List<MetricFact.Runtime> facts = projector.project(observation(
                terminal, RetrievalObservationStatus.FAILED,
                "COVERAGE_CHECK_FAILED", 0, Set.of(SPACE)
        ));

        MetricDimensions dimensions = facts.getFirst().dimensions();
        assertEquals("FAILED", dimensions.require(MetricDimensions.Key.STATUS));
        assertEquals("FAILED",
                dimensions.require(MetricDimensions.Key.TECHNICAL_STATUS));
        assertEquals("CHECK_FAILED",
                dimensions.require(MetricDimensions.Key.TERMINAL_STATUS));
        assertEquals("COVERAGE_CHECK_FAILED",
                dimensions.require(MetricDimensions.Key.STOP_REASON));
        assertFalse(has(facts, "retrieval.request.technical_success.rate"));
    }

    private static RetrievalObservation observation(RetrievalObservationPayload payload) {
        return observation(payload, RetrievalObservationStatus.SUCCEEDED,
                "NONE", 0, Set.of(SPACE));
    }

    private static RetrievalObservation observation(
            RetrievalObservationPayload payload,
            RetrievalObservationStatus status,
            String reasonCode,
            int attemptIndex,
            Set<KnowledgeSpaceId> spaces
    ) {
        return event(UUID.randomUUID(), UUID.randomUUID(), 1L, payload, status,
                reasonCode, attemptIndex, spaces,
                payload instanceof RetrievalObservationPayload.ExecutionStarted
                        ? RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT : CONFIG);
    }

    private static RetrievalObservation started(
            UUID executionId,
            UUID requestId,
            long sequence
    ) {
        var payload = new RetrievalObservationPayload.ExecutionStarted(
                fingerprint(), 8, 1
        );
        return event(executionId, requestId, sequence, payload,
                RetrievalObservationStatus.STARTED, "NONE", 0, Set.of(),
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT);
    }

    private static RetrievalObservation configured(
            UUID executionId,
            UUID requestId,
            long sequence
    ) {
        var payload = new RetrievalObservationPayload.ConfigurationResolved(
                SPACE, 1L, List.of(), List.of()
        );
        return event(executionId, requestId, sequence, payload,
                RetrievalObservationStatus.SUCCEEDED, "NONE", 0, Set.of(SPACE), CONFIG);
    }

    private static RetrievalObservation coverage(
            UUID executionId,
            UUID requestId,
            long sequence,
            RetrievalObservationPayload.CoverageTerminalStatus status
    ) {
        boolean sufficient = status
                == RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT;
        var payload = new RetrievalObservationPayload.CoverageCheckCompleted(
                COMPONENT, 4, 4, 1,
                RetrievalObservationPayload.OptionalCount.of(sufficient ? 1 : 0),
                RetrievalObservationPayload.OptionalScore.of(sufficient ? 0.8D : 0.4D),
                RetrievalObservationPayload.OptionalScore.of(0.7D),
                status, status.name(), RetrievalObservationPayload.UsageCount.none()
        );
        return event(executionId, requestId, sequence, payload,
                RetrievalObservationStatus.SUCCEEDED, "NONE", 0, Set.of(SPACE), CONFIG);
    }

    private static RetrievalObservation terminal(
            UUID executionId,
            UUID requestId,
            long sequence,
            RetrievalTerminalStatus status,
            RetrievalStopReason stopReason
    ) {
        var payload = new RetrievalObservationPayload.ExecutionTerminal(
                status, stopReason, 2, 4, false, List.of()
        );
        return event(executionId, requestId, sequence, payload,
                RetrievalObservationStatus.SUCCEEDED, "NONE", 1, Set.of(SPACE), CONFIG);
    }

    private static RetrievalObservation event(
            UUID executionId,
            UUID requestId,
            long sequence,
            RetrievalObservationPayload payload,
            RetrievalObservationStatus status,
            String reason,
            int attemptIndex,
            Set<KnowledgeSpaceId> spaces,
            String config
    ) {
        Instant startedAt = NOW.plusMillis(sequence * 10L);
        return new RetrievalObservation(
                UUID.randomUUID(), executionId, requestId, sequence,
                RetrievalObservationPurpose.ONLINE, TENANT, spaces,
                0, attemptIndex, payload.stage(), status, reason, config,
                startedAt, startedAt,
                RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
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

    private static void assertRatio(
            List<MetricFact.Runtime> facts,
            String key,
            double numerator,
            double denominator
    ) {
        assertEquals(numerator, value(facts, key, MetricFact.Aggregation.RATIO_NUMERATOR));
        assertEquals(denominator,
                value(facts, key, MetricFact.Aggregation.RATIO_DENOMINATOR));
    }

    private static double count(List<MetricFact.Runtime> facts, String key) {
        return value(facts, key, MetricFact.Aggregation.COUNT);
    }

    private static double distribution(List<MetricFact.Runtime> facts, String key) {
        return value(facts, key, MetricFact.Aggregation.DISTRIBUTION);
    }

    private static double sum(List<MetricFact.Runtime> facts, String key) {
        return value(facts, key, MetricFact.Aggregation.SUM);
    }

    private static double value(
            List<MetricFact.Runtime> facts,
            String key,
            MetricFact.Aggregation aggregation
    ) {
        return facts.stream()
                .filter(fact -> key.equals(fact.metricKey()))
                .filter(fact -> fact.aggregation() == aggregation)
                .findFirst()
                .orElseThrow()
                .value();
    }
}
