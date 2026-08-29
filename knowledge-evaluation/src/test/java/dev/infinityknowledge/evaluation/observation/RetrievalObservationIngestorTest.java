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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证事件乱序、缺片、终态缺失和幂等重投的聚合语义。
 */
class RetrievalObservationIngestorTest {
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");
    private static final String CONFIG = "a".repeat(64);
    private static final String NEXT_CONFIG = "b".repeat(64);
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("space-a");
    private static final KnowledgeSpaceId NEXT_SPACE = new KnowledgeSpaceId("space-b");

    @Test
    void becomesCompleteAfterOutOfOrderGapIsFilled() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        var afterAnalysis = ingestor.ingest(analysis(executionId, requestId, 2L));
        assertEquals(ObservationCompleteness.INCOMPLETE, afterAnalysis.execution().completeness());
        assertTrue(afterAnalysis.execution().incompleteReasons().contains(
                ObservationIncompleteReason.STARTED_MISSING
        ));
        assertTrue(afterAnalysis.execution().incompleteReasons().contains(
                ObservationIncompleteReason.TERMINAL_MISSING
        ));

        var afterTerminal = ingestor.ingest(terminal(executionId, requestId, 3L));
        assertEquals(List.of(0L, 1L), afterTerminal.execution().missingSequences());

        ingestor.ingest(started(executionId, requestId));
        var complete = ingestor.ingest(configuration(executionId, requestId, 1L));

        assertEquals(ObservationCompleteness.COMPLETE, complete.execution().completeness());
        assertTrue(complete.execution().incompleteReasons().isEmpty());
        assertEquals(List.of(0L, 1L, 2L, 3L), complete.execution().events().stream()
                .map(RetrievalObservation::sequence).toList());
    }

    @Test
    void identicalEventIdRedeliveryIsIdempotent() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        var event = started(UUID.randomUUID(), UUID.randomUUID());

        assertFalse(ingestor.ingest(event).duplicate());
        assertTrue(ingestor.ingest(event).duplicate());
        assertEquals(1, ingestor.execution(event.executionId()).orElseThrow().events().size());
    }

    @Test
    void rejectsSameEventIdWithDifferentContent() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var first = started(eventId, executionId, requestId, CONFIG);
        var changed = started(eventId, executionId, requestId, "b".repeat(64));

        ingestor.ingest(first);

        assertThrows(ObservationConflictException.class, () -> ingestor.ingest(changed));
    }

    @Test
    void rejectsDifferentEventsUsingTheSameSequence() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ingestor.ingest(started(executionId, requestId));

        assertThrows(ObservationConflictException.class, () ->
                ingestor.ingest(observation(
                        UUID.randomUUID(), executionId, requestId, 0L, CONFIG,
                        RetrievalObservationStatus.STARTED, "NONE",
                        new RetrievalObservationPayload.ExecutionStarted(
                                fingerprint("c"), 4, 0
                        )
                ))
        );
    }

    @Test
    void rejectsEventsAfterKnownTerminalSequence() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ingestor.ingest(started(executionId, requestId));
        ingestor.ingest(terminal(executionId, requestId, 1L));

        assertThrows(ObservationConflictException.class, () ->
                ingestor.ingest(analysis(executionId, requestId, 2L))
        );
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor(Set.of(1));
        var event = started(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CONFIG, 2
        );

        assertThrows(IllegalArgumentException.class, () -> ingestor.ingest(event));
    }

    @Test
    void resolvesFingerprintAfterUnresolvedStartedEvent() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        ingestor.ingest(started(
                UUID.randomUUID(), executionId, requestId,
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT
        ));
        ingestor.ingest(observationAtVisit(
                UUID.randomUUID(), executionId, requestId, 1L,
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.SpaceRoutingCompleted(
                        1,
                        List.of(new RetrievalObservationPayload.RankedSpace(SPACE, 1))
                ), 0, Set.of(SPACE)
        ));
        ingestor.ingest(configuration(executionId, requestId, 2L));
        var terminal = ingestor.ingest(terminal(executionId, requestId, 3L));

        assertEquals(ObservationCompleteness.COMPLETE, terminal.execution().completeness());
        assertEquals(
                List.of(new VisitedRetrievalConfiguration(0, SPACE, 1L, CONFIG)),
                terminal.execution().visitedConfigurations()
        );
    }

    @Test
    void rejectsResolvedFingerprintChangingWithinVisit() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ingestor.ingest(started(executionId, requestId));
        ingestor.ingest(configuration(executionId, requestId, 1L));

        assertThrows(ObservationConflictException.class, () -> ingestor.ingest(observation(
                UUID.randomUUID(), executionId, requestId, 2L, NEXT_CONFIG,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.QueryAnalysisCompleted(
                        fingerprint("d"), Set.of(RetrievalChannel.KEYWORD), 8,
                        new RetrievalObservationPayload.ComponentVersion(
                                "query-analyzer", "built-in", "none", "v1"
                        )
                )
        )));
    }

    @Test
    void rejectsUnresolvedFingerprintAfterResolution() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ingestor.ingest(started(executionId, requestId));
        ingestor.ingest(configuration(executionId, requestId, 1L));

        assertThrows(ObservationConflictException.class, () -> ingestor.ingest(observation(
                UUID.randomUUID(), executionId, requestId, 2L,
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                RetrievalObservationStatus.FAILED, "CONFIG_RESOLUTION_FAILED",
                new RetrievalObservationPayload.ExecutionTerminal(
                        RetrievalTerminalStatus.TECHNICAL_FAILED,
                        RetrievalStopReason.TECHNICAL_FAILURE,
                        0,
                        0,
                        false,
                        List.of()
                )
        )));
    }

    @Test
    void allowsNewVisitToResolveItsOwnConfiguration() {
        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        ingestor.ingest(started(executionId, requestId));
        ingestor.ingest(configuration(executionId, requestId, 1L));
        ingestor.ingest(observationAtVisit(
                UUID.randomUUID(), executionId, requestId, 2L, CONFIG,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.SpaceChanged(
                        SPACE, NEXT_SPACE, "NEXT_SPACE"
                ), 0, Set.of(SPACE, NEXT_SPACE)
        ));
        ingestor.ingest(observationAtVisit(
                UUID.randomUUID(), executionId, requestId, 3L,
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.SpaceRoutingCompleted(
                        1,
                        List.of(new RetrievalObservationPayload.RankedSpace(NEXT_SPACE, 1))
                ), 1, Set.of(NEXT_SPACE)
        ));
        ingestor.ingest(observationAtVisit(
                UUID.randomUUID(), executionId, requestId, 4L, NEXT_CONFIG,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.ConfigurationResolved(
                        NEXT_SPACE, 2L, List.of(), List.of()
                ), 1, Set.of(NEXT_SPACE)
        ));
        var terminal = ingestor.ingest(observationAtVisit(
                UUID.randomUUID(), executionId, requestId, 5L, NEXT_CONFIG,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.ExecutionTerminal(
                        RetrievalTerminalStatus.NOT_EVALUATED,
                        RetrievalStopReason.COVERAGE_DISABLED,
                        1,
                        0,
                        false,
                        List.of()
                ),
                1, Set.of(NEXT_SPACE)
        ));

        assertEquals(ObservationCompleteness.COMPLETE, terminal.execution().completeness());
        assertEquals(
                List.of(
                        new VisitedRetrievalConfiguration(0, SPACE, 1L, CONFIG),
                        new VisitedRetrievalConfiguration(1, NEXT_SPACE, 2L, NEXT_CONFIG)
                ),
                terminal.execution().visitedConfigurations()
        );
    }

    private static RetrievalObservation started(UUID executionId, UUID requestId) {
        return started(
                UUID.randomUUID(), executionId, requestId,
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT
        );
    }

    private static RetrievalObservation started(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            String config
    ) {
        return started(
                eventId,
                executionId,
                requestId,
                config,
                RetrievalObservation.CURRENT_SCHEMA_VERSION
        );
    }

    private static RetrievalObservation started(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            String config,
            int schemaVersion
    ) {
        return observation(
                eventId, executionId, requestId, 0L, config,
                RetrievalObservationStatus.STARTED, "NONE",
                new RetrievalObservationPayload.ExecutionStarted(
                        fingerprint("b"), 8, 0
                ), schemaVersion
        );
    }

    private static RetrievalObservation configuration(
            UUID executionId,
            UUID requestId,
            long sequence
    ) {
        return observation(
                UUID.randomUUID(), executionId, requestId, sequence, CONFIG,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.ConfigurationResolved(
                        SPACE, 1L, List.of(), List.of()
                )
        );
    }

    private static RetrievalObservation analysis(
            UUID executionId,
            UUID requestId,
            long sequence
    ) {
        return observation(
                UUID.randomUUID(), executionId, requestId, sequence, CONFIG,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.QueryAnalysisCompleted(
                        fingerprint("c"),
                        Set.of(RetrievalChannel.KEYWORD),
                        16,
                        new RetrievalObservationPayload.ComponentVersion(
                                "query-analyzer", "built-in", "none", "v1"
                        )
                )
        );
    }

    private static RetrievalObservation terminal(
            UUID executionId,
            UUID requestId,
            long sequence
    ) {
        return observation(
                UUID.randomUUID(), executionId, requestId, sequence, CONFIG,
                RetrievalObservationStatus.SUCCEEDED, "NONE",
                new RetrievalObservationPayload.ExecutionTerminal(
                        RetrievalTerminalStatus.SUFFICIENT,
                        RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED,
                        1,
                        3,
                        false,
                        List.of()
                )
        );
    }

    private static RetrievalObservation observation(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            long sequence,
            String config,
            RetrievalObservationStatus status,
            String reason,
            RetrievalObservationPayload payload
    ) {
        return observation(
                eventId, executionId, requestId, sequence, config,
                status, reason, payload, RetrievalObservation.CURRENT_SCHEMA_VERSION
        );
    }

    private static RetrievalObservation observationAtVisit(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            long sequence,
            String config,
            RetrievalObservationStatus status,
            String reason,
            RetrievalObservationPayload payload,
            int visitIndex,
            Set<KnowledgeSpaceId> spaceIds
    ) {
        return new RetrievalObservation(
                eventId, executionId, requestId, sequence,
                RetrievalObservationPurpose.ONLINE, new TenantId("tenant-a"), spaceIds,
                visitIndex, 0, payload.stage(), status, reason, config,
                NOW, NOW, RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
        );
    }

    private static RetrievalObservation observation(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            long sequence,
            String config,
            RetrievalObservationStatus status,
            String reason,
            RetrievalObservationPayload payload,
            int schemaVersion
    ) {
        Set<KnowledgeSpaceId> spaceIds = payload
                instanceof RetrievalObservationPayload.ConfigurationResolved value
                ? Set.of(value.spaceId()) : Set.of();
        return new RetrievalObservation(
                eventId, executionId, requestId, sequence,
                RetrievalObservationPurpose.ONLINE, new TenantId("tenant-a"), spaceIds,
                0, 0, payload.stage(), status, reason, config,
                NOW, NOW, schemaVersion, payload
        );
    }

    private static RetrievalObservationPayload.TextFingerprint fingerprint(String digit) {
        return new RetrievalObservationPayload.TextFingerprint(
                "HMAC_SHA256", "key-v1", digit.repeat(64)
        );
    }
}
