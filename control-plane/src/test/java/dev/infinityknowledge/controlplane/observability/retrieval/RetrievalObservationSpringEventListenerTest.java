package dev.infinityknowledge.controlplane.observability.retrieval;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.evaluation.observation.ObservationConflictException;
import dev.infinityknowledge.evaluation.observation.RetrievalObservationProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 验证 Spring Event 消费在事务内执行且不会吞掉幂等冲突。 */
class RetrievalObservationSpringEventListenerTest {

    @Test
    void processesObservationInsideTransaction() {
        RetrievalObservationProcessor processor = mock(RetrievalObservationProcessor.class);
        TransactionOperations transaction = invokingTransaction();
        var listener = new RetrievalObservationSpringEventListener(processor, transaction);
        RetrievalObservation observation = started();

        listener.onObservation(observation);

        verify(processor).process(observation);
    }

    @Test
    void propagatesObservationConflict() {
        RetrievalObservationProcessor processor = mock(RetrievalObservationProcessor.class);
        TransactionOperations transaction = invokingTransaction();
        var listener = new RetrievalObservationSpringEventListener(processor, transaction);
        RetrievalObservation observation = started();
        doThrow(new ObservationConflictException("conflict"))
                .when(processor).process(observation);

        assertThrows(
                ObservationConflictException.class,
                () -> listener.onObservation(observation)
        );
    }

    @SuppressWarnings("unchecked")
    private static TransactionOperations invokingTransaction() {
        TransactionOperations transaction = mock(TransactionOperations.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any(Consumer.class));
        return transaction;
    }

    private static RetrievalObservation started() {
        Instant now = Instant.parse("2026-08-26T01:02:03Z");
        var payload = new RetrievalObservationPayload.ExecutionStarted(
                new RetrievalObservationPayload.TextFingerprint(
                        "HMAC_SHA256", "key-v1", "a".repeat(64)
                ),
                8,
                0
        );
        return new RetrievalObservation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0L,
                RetrievalObservationPurpose.ONLINE, new TenantId("tenant-a"), Set.of(),
                0, 0, payload.stage(), RetrievalObservationStatus.STARTED, "NONE",
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                now, now, RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
        );
    }
}
