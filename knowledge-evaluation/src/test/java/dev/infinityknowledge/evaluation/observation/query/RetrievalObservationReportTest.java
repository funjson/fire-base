package dev.infinityknowledge.evaluation.observation.query;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationCompleteness;
import dev.infinityknowledge.evaluation.observation.ObservationIncompleteReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证只读报告不携带 payload，并把指标 Space 纳入授权范围。 */
class RetrievalObservationReportTest {
    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");

    @Test
    void keepsOnlySafeEventFactsAndIncludesMetricSpacesInAuthorizationScope() {
        UUID eventId = UUID.randomUUID();
        RetrievalObservationReport.EventFact event = event(eventId);
        MetricFact.Runtime metric = new MetricFact.Runtime(
                eventId,
                UUID.randomUUID(),
                TENANT,
                "retrieval.request.count",
                1,
                MetricFact.Aggregation.COUNT,
                1.0D,
                MetricDimensions.builder()
                        .space(SPACE)
                        .config(RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT)
                        .status(RetrievalObservationStatus.STARTED)
                        .purpose(RetrievalObservationPurpose.ONLINE)
                        .timeSlice(NOW, MetricDimensions.TimeSliceGranularity.HOUR)
                        .build(),
                NOW
        );
        RetrievalObservationReport report = new RetrievalObservationReport(
                TENANT,
                UUID.randomUUID(),
                metric.executionId(),
                RetrievalObservationPurpose.ONLINE,
                ObservationCompleteness.INCOMPLETE,
                Set.of(ObservationIncompleteReason.TERMINAL_MISSING),
                List.of(),
                List.of(),
                List.of(event),
                List.of(RetrievalObservationReport.MetricFactView.from(metric))
        );

        assertEquals(Set.of(SPACE), report.involvedSpaceIds());
        Set<String> eventFields = Arrays.stream(
                        RetrievalObservationReport.EventFact.class.getRecordComponents()
                )
                .map(component -> component.getName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertFalse(eventFields.contains("payload"));
        assertFalse(eventFields.contains("query"));
        assertFalse(eventFields.contains("queryFingerprint"));
    }

    @Test
    void rejectsMetricFactsWhoseSourceEventIsNotInTheReport() {
        RetrievalObservationReport.MetricFactView orphan =
                new RetrievalObservationReport.MetricFactView(
                        UUID.randomUUID(),
                        RetrievalObservationReport.MetricType.RUNTIME,
                        "retrieval.request.count",
                        1,
                        MetricFact.Aggregation.COUNT,
                        1.0D,
                        MetricDimensions.builder()
                                .config(RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT)
                                .status(RetrievalObservationStatus.STARTED)
                                .purpose(RetrievalObservationPurpose.ONLINE)
                                .timeSlice(
                                        NOW,
                                        MetricDimensions.TimeSliceGranularity.HOUR
                                )
                                .build(),
                        NOW,
                        null
                );

        assertThrows(IllegalArgumentException.class, () -> new RetrievalObservationReport(
                TENANT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                RetrievalObservationPurpose.ONLINE,
                ObservationCompleteness.INCOMPLETE,
                Set.of(ObservationIncompleteReason.TERMINAL_MISSING),
                List.of(),
                List.of(),
                List.of(event(UUID.randomUUID())),
                List.of(orphan)
        ));
    }

    private static RetrievalObservationReport.EventFact event(UUID eventId) {
        return new RetrievalObservationReport.EventFact(
                eventId,
                0L,
                Set.of(),
                0,
                0,
                RetrievalObservationStage.EXECUTION_STARTED,
                RetrievalObservationStatus.STARTED,
                "NONE",
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                NOW,
                NOW,
                0L,
                1,
                0,
                RetrievalObservation.CURRENT_SCHEMA_VERSION
        );
    }
}
