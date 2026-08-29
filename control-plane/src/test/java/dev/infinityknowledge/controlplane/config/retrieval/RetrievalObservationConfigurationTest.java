package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalTextFingerprinter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 验证生产观测端口通过 Spring Event 发布完整不可变事实。 */
class RetrievalObservationConfigurationTest {

    @Test
    void publishesTheSameObservationThroughSpringEvent() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        RetrievalObservationPublisher publisher = new RetrievalObservationConfiguration()
                .retrievalObservationPublisher(events);
        Instant now = Instant.parse("2026-08-26T08:00:00Z");
        RetrievalObservationPayload.ExecutionStarted payload =
                new RetrievalObservationPayload.ExecutionStarted(
                        new RetrievalObservationPayload.TextFingerprint(
                                "HMAC_SHA256",
                                "key-v1",
                                "a".repeat(64)
                        ),
                        8,
                        1
                );
        RetrievalObservation observation = new RetrievalObservation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                0L,
                RetrievalObservationPurpose.ONLINE,
                new TenantId("tenant-a"),
                Set.of(),
                0,
                0,
                payload.stage(),
                RetrievalObservationStatus.STARTED,
                "NONE",
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                now,
                now,
                RetrievalObservation.CURRENT_SCHEMA_VERSION,
                payload
        );

        publisher.publish(observation);

        verify(events).publishEvent(observation);
    }

    @Test
    void createsVersionedHmacTextFingerprinter() {
        RetrievalTextFingerprinter fingerprinter = new RetrievalObservationConfiguration()
                .retrievalTextFingerprinter(
                        new RetrievalObservationFingerprintProperties(
                                "test-only-retrieval-observation-key-0001",
                                "key-v1"
                        )
                );

        RetrievalObservationPayload.TextFingerprint fingerprint =
                fingerprinter.fingerprint("ERR-1001 如何恢复");

        assertEquals("HMAC_SHA256", fingerprint.algorithm());
        assertEquals("key-v1", fingerprint.keyVersion());
        assertEquals(64, fingerprint.value().length());
    }

    @Test
    void keepsAReplacementPublisherForFutureMqAdapters() {
        RetrievalObservationPublisher replacement = mock(
                RetrievalObservationPublisher.class
        );
        RetrievalTextFingerprinter replacementFingerprinter = mock(
                RetrievalTextFingerprinter.class
        );

        new ApplicationContextRunner()
                .withUserConfiguration(RetrievalObservationConfiguration.class)
                .withBean(RetrievalObservationPublisher.class, () -> replacement)
                .withBean(
                        RetrievalTextFingerprinter.class,
                        () -> replacementFingerprinter
                )
                .run(context -> assertSame(
                        replacement,
                        context.getBean(RetrievalObservationPublisher.class)
                ));
    }
}
