package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证运行事实和离线 Gold 事实在类型及维度上的硬隔离。
 */
class MetricFactTest {
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");
    private static final String CONFIG = "a".repeat(64);

    @Test
    void runtimeFactRejectsQueryCaseDimension() {
        MetricDimensions dimensions = dimensions(RetrievalObservationPurpose.ONLINE)
                .queryCase(UUID.randomUUID())
                .build();

        assertThrows(IllegalArgumentException.class, () -> new MetricFact.Runtime(
                UUID.randomUUID(), UUID.randomUUID(), new TenantId("tenant-a"),
                "retrieval.request.count", 1,
                MetricFact.Aggregation.COUNT, 1.0D, dimensions, NOW
        ));
    }

    @Test
    void offlineGoldFactRequiresEvaluationPurposeAndMatchingCase() {
        UUID caseId = UUID.randomUUID();
        MetricDimensions online = dimensions(RetrievalObservationPurpose.ONLINE)
                .queryCase(caseId)
                .build();

        assertThrows(IllegalArgumentException.class, () -> new MetricFact.OfflineGold(
                UUID.randomUUID(), UUID.randomUUID(), new TenantId("tenant-a"),
                UUID.randomUUID(), 1L, caseId,
                "retrieval.chunk.recall", 1, MetricFact.Aggregation.MACRO_AVERAGE,
                0.5D, online, NOW
        ));

        UUID differentCase = UUID.randomUUID();
        MetricDimensions evaluation = dimensions(RetrievalObservationPurpose.EVALUATION)
                .queryCase(caseId)
                .build();
        assertThrows(IllegalArgumentException.class, () -> new MetricFact.OfflineGold(
                UUID.randomUUID(), UUID.randomUUID(), new TenantId("tenant-a"),
                UUID.randomUUID(), 1L, differentCase,
                "retrieval.chunk.recall", 1, MetricFact.Aggregation.MACRO_AVERAGE,
                0.5D, evaluation, NOW
        ));
    }

    @Test
    void offlineGoldFactKeepsMetricDefinitionAndDatasetVersionsIndependent() {
        UUID caseId = UUID.randomUUID();
        MetricDimensions dimensions = dimensions(RetrievalObservationPurpose.EVALUATION)
                .queryCase(caseId)
                .strategy("BASELINE")
                .attempt(0)
                .build();

        MetricFact.OfflineGold fact = new MetricFact.OfflineGold(
                UUID.randomUUID(), UUID.randomUUID(), new TenantId("tenant-a"),
                UUID.randomUUID(), 7L, caseId,
                "retrieval.chunk.recall", 3, MetricFact.Aggregation.MACRO_AVERAGE,
                0.75D, dimensions, NOW
        );

        assertEquals(7L, fact.datasetVersion());
        assertEquals(3, fact.metricDefinitionVersion());
        assertEquals("BASELINE", fact.dimensions().require(MetricDimensions.Key.STRATEGY));
    }

    @Test
    void runtimeProjectorProducesOnlyOperationalFactsForFailedTerminal() {
        UUID executionId = UUID.randomUUID();
        var payload = new RetrievalObservationPayload.ExecutionTerminal(
                RetrievalTerminalStatus.TECHNICAL_FAILED,
                RetrievalStopReason.TECHNICAL_FAILURE,
                0,
                0,
                false,
                List.of()
        );
        var observation = new RetrievalObservation(
                UUID.randomUUID(), executionId, UUID.randomUUID(), 1L,
                RetrievalObservationPurpose.ONLINE,
                new TenantId("tenant-a"),
                Set.of(), 0, 0, payload.stage(), RetrievalObservationStatus.FAILED,
                "TECHNICAL_FAILED", RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                NOW, NOW, 1, payload
        );

        List<MetricFact.Runtime> facts = new RuntimeMetricFactProjector().project(observation);

        assertEquals(6, facts.size());
        MetricFact.Runtime stage = facts.stream()
                .filter(fact -> "retrieval.stage.event.count".equals(fact.metricKey()))
                .findFirst()
                .orElseThrow();
        assertEquals(1.0D, stage.value());
        assertEquals(
                "ONLINE",
                stage.dimensions().require(MetricDimensions.Key.PURPOSE)
        );
        assertEquals(
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                stage.dimensions().require(MetricDimensions.Key.CONFIG)
        );
        assertEquals(
                "FAILED",
                stage.dimensions().require(MetricDimensions.Key.TECHNICAL_STATUS)
        );
        assertEquals(
                "TECHNICAL_FAILED",
                stage.dimensions().require(MetricDimensions.Key.TERMINAL_STATUS)
        );
        assertEquals(
                "TECHNICAL_FAILURE",
                stage.dimensions().require(MetricDimensions.Key.STOP_REASON)
        );
    }

    private static MetricDimensions.Builder dimensions(
            RetrievalObservationPurpose purpose
    ) {
        return MetricDimensions.builder()
                .config(CONFIG)
                .status(RetrievalObservationStatus.SUCCEEDED)
                .purpose(purpose)
                .timeSlice(NOW, MetricDimensions.TimeSliceGranularity.HOUR);
    }
}
