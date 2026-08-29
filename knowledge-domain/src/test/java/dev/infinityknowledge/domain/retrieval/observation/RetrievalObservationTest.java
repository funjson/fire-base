package dev.infinityknowledge.domain.retrieval.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证检索观测协议的生命周期、强类型载荷和隐私边界。
 */
class RetrievalObservationTest {
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");
    private static final String CONFIG = "a".repeat(64);
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("space-a");

    @Test
    void acceptsStartedAndFailedTerminalEvents() {
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        RetrievalObservation started = observation(
                UUID.randomUUID(), executionId, requestId, 0L,
                RetrievalObservationStatus.STARTED, "NONE",
                new RetrievalObservationPayload.ExecutionStarted(
                        fingerprint("b"), 8, 0
                ), RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT
        );
        RetrievalObservation terminal = observation(
                UUID.randomUUID(), executionId, requestId, 1L,
                RetrievalObservationStatus.FAILED, "RETRIEVER_FAILED",
                new RetrievalObservationPayload.ExecutionTerminal(
                        RetrievalTerminalStatus.TECHNICAL_FAILED,
                        RetrievalStopReason.TECHNICAL_FAILURE,
                        0,
                        0,
                        false,
                        List.of()
                ),
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT
        );

        assertEquals(RetrievalObservationStage.EXECUTION_STARTED, started.stage());
        assertTrue(terminal.terminal());
        assertEquals(RetrievalObservationStatus.FAILED, terminal.status());
    }

    @Test
    void canonicalizesTimestampsForDurableStoreRoundTrips() {
        Instant runtimeTime = Instant.parse("2026-08-26T01:02:03.123456789Z");
        var payload = new RetrievalObservationPayload.ExecutionStarted(
                fingerprint("d"), 8, 1
        );

        RetrievalObservation observation = new RetrievalObservation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0L,
                RetrievalObservationPurpose.ONLINE, new TenantId("tenant-a"), Set.of(),
                0, 0, payload.stage(), RetrievalObservationStatus.STARTED, "NONE",
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                runtimeTime, runtimeTime.plusNanos(100),
                RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
        );

        assertEquals(
                Instant.parse("2026-08-26T01:02:03.123456Z"),
                observation.startedAt()
        );
        assertEquals(observation.startedAt(), observation.completedAt());
    }

    @Test
    void successfulConfigurationAndTerminalRequireResolvedFingerprint() {
        var configuration = new RetrievalObservationPayload.ConfigurationResolved(
                SPACE, 1L, List.of(), List.of()
        );
        var terminal = new RetrievalObservationPayload.ExecutionTerminal(
                RetrievalTerminalStatus.NOT_EVALUATED,
                RetrievalStopReason.COVERAGE_DISABLED,
                1,
                0,
                false,
                List.of()
        );

        assertThrows(IllegalArgumentException.class, () -> observation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                RetrievalObservationStatus.SUCCEEDED, "NONE", configuration,
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT
        ));
        assertThrows(IllegalArgumentException.class, () -> observation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                RetrievalObservationStatus.SUCCEEDED, "NONE", terminal,
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT
        ));
    }

    @Test
    void rejectsTerminalStatusAndStopReasonFromDifferentOutcomes() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetrievalObservationPayload.ExecutionTerminal(
                        RetrievalTerminalStatus.SUFFICIENT,
                        RetrievalStopReason.RETRIEVAL_BUDGET_EXHAUSTED,
                        1,
                        1,
                        false,
                        List.of()
                )
        );
    }

    @Test
    void stageFailureRequiresFailedTechnicalStatus() {
        var payload = new RetrievalObservationPayload.StageFailed(
                "SPACE_ROUTING",
                1
        );

        assertThrows(IllegalArgumentException.class, () -> observation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                RetrievalObservationStatus.DEGRADED,
                "TECHNICAL_FAILURE",
                payload
        ));
        RetrievalObservation failed = observation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                RetrievalObservationStatus.FAILED,
                "TECHNICAL_FAILURE",
                payload
        );

        assertEquals(RetrievalObservationStage.STAGE_FAILURE, failed.stage());
    }

    @Test
    void rejectsPayloadWhoseStageDiffersFromEnvelope() {
        var payload = new RetrievalObservationPayload.ExecutionStarted(
                fingerprint("c"), 8, 1
        );

        assertThrows(IllegalArgumentException.class, () -> new RetrievalObservation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1L,
                RetrievalObservationPurpose.ONLINE, new TenantId("tenant-a"), Set.of(),
                0, 0, RetrievalObservationStage.QUERY_ANALYSIS,
                RetrievalObservationStatus.SUCCEEDED, "NONE", CONFIG,
                NOW, NOW, RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
        ));
    }

    @Test
    void rejectsPlainTextWhereFingerprintIsRequired() {
        assertThrows(IllegalArgumentException.class, () ->
                new RetrievalObservationPayload.TextFingerprint(
                        "HMAC_SHA256", "key-v1", "用户的原始查询"
                )
        );
    }

    @Test
    void payloadProtocolContainsNoRawTextOrKnowledgeDisplayFields() {
        Set<String> forbidden = Set.of(
                "text", "query", "content", "title", "sourceuri", "prompt", "response"
        );
        for (Class<?> payloadType : RetrievalObservationPayload.class.getPermittedSubclasses()) {
            List<String> componentNames = Arrays.stream(payloadType.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(String::toLowerCase)
                    .toList();
            assertTrue(
                    componentNames.stream().noneMatch(forbidden::contains),
                    () -> payloadType.getSimpleName() + " contains a forbidden raw-data field"
            );
        }
    }

    private static RetrievalObservation observation(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            long sequence,
            RetrievalObservationStatus status,
            String reasonCode,
            RetrievalObservationPayload payload
    ) {
        return observation(
                eventId,
                executionId,
                requestId,
                sequence,
                status,
                reasonCode,
                payload,
                CONFIG
        );
    }

    private static RetrievalObservation observation(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            long sequence,
            RetrievalObservationStatus status,
            String reasonCode,
            RetrievalObservationPayload payload,
            String configFingerprint
    ) {
        Set<KnowledgeSpaceId> spaceIds = payload
                instanceof RetrievalObservationPayload.ConfigurationResolved value
                ? Set.of(value.spaceId()) : Set.of();
        return new RetrievalObservation(
                eventId,
                executionId,
                requestId,
                sequence,
                RetrievalObservationPurpose.ONLINE,
                new TenantId("tenant-a"),
                spaceIds,
                0,
                0,
                payload.stage(),
                status,
                reasonCode,
                configFingerprint,
                NOW,
                NOW,
                RetrievalObservation.CURRENT_SCHEMA_VERSION,
                payload
        );
    }

    private static RetrievalObservationPayload.TextFingerprint fingerprint(String digit) {
        return new RetrievalObservationPayload.TextFingerprint(
                "HMAC_SHA256", "key-v1", digit.repeat(64)
        );
    }
}
