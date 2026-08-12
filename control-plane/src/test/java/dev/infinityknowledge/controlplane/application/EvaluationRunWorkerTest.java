package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.config.AsyncRunProperties;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.evaluation.EvaluationStore;
import dev.infinityknowledge.spi.KnowledgeGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationRunWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-11T01:00:00Z");

    @Test
    void heartbeatsEveryCaseAndCompletesWithLease() {
        EvaluationStore store = mock(EvaluationStore.class);
        KnowledgeGateway gateway = mock(KnowledgeGateway.class);
        var lease = lease();
        var evaluationCase = evaluationCase(lease.datasetId());
        when(store.heartbeat(any(), any(), any())).thenReturn(true);
        when(gateway.retrieve(any())).thenAnswer(invocation -> {
            var query = (dev.infinityknowledge.domain.retrieval.KnowledgeQuery)
                    invocation.getArgument(0);
            return new EvidenceBundle(
                    query.requestId(), UUID.randomUUID(), query.principal().tenantId(),
                    List.of(), false, List.of(), NOW
            );
        });
        when(store.completeRun(any(), any(), any())).thenReturn(true);

        worker(store, gateway).execute(lease, List.of(evaluationCase));

        verify(store).heartbeat(lease, NOW.plusSeconds(120), NOW);
        verify(store).completeRun(any(), any(), any());
        verify(store, never()).failRun(any(), any(), any());
    }

    @Test
    void leaseLossAbortsReadOnlyRerunWithoutStaleTerminalWrite() {
        EvaluationStore store = mock(EvaluationStore.class);
        when(store.heartbeat(any(), any(), any())).thenReturn(false);
        var lease = lease();

        worker(store, mock(KnowledgeGateway.class))
                .execute(lease, List.of(evaluationCase(lease.datasetId())));

        verify(store, never()).completeRun(any(), any(), any());
        verify(store, never()).failRun(any(), any(), any());
    }

    private static EvaluationRunWorker worker(
            EvaluationStore store,
            KnowledgeGateway gateway
    ) {
        return new EvaluationRunWorker(
                store,
                gateway,
                new AsyncRunProperties(
                        Duration.ofMinutes(2),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(10),
                        2
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static EvaluationStore.WorkLease lease() {
        TenantId tenantId = new TenantId("tenant-a");
        UUID caseId = UUID.randomUUID();
        return new EvaluationStore.WorkLease(
                tenantId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(caseId),
                Map.of("topK", 8),
                new PrincipalContext(
                        tenantId,
                        new PrincipalId("admin"),
                        Set.of("knowledge-admin"),
                        Set.of("engineering"),
                        false
                ),
                "worker-a",
                1,
                NOW.plusSeconds(120)
        );
    }

    private static EvaluationStore.Case evaluationCase(UUID datasetId) {
        return new EvaluationStore.Case(
                UUID.randomUUID(),
                datasetId,
                "Redis timeout",
                Set.of("engineering"),
                Set.of(new DocumentId(UUID.randomUUID()).value()),
                Set.of(),
                8,
                Map.of(),
                NOW
        );
    }
}
