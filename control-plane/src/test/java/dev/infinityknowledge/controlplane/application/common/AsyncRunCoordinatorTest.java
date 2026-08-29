package dev.infinityknowledge.controlplane.application.common;

import dev.infinityknowledge.controlplane.config.AsyncRunProperties;
import dev.infinityknowledge.controlplane.application.connector.ConnectorRunCoordinator;
import dev.infinityknowledge.controlplane.application.connector.ConnectorSyncWorker;
import dev.infinityknowledge.controlplane.application.evaluation.EvaluationRunCoordinator;
import dev.infinityknowledge.controlplane.application.evaluation.EvaluationRunWorker;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.EvaluationStore;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import dev.infinityknowledge.spi.connector.SourceConnectorProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncRunCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-11T02:00:00Z");

    @Test
    void connectorRecoveryClaimsBoundedBatchAndDispatchesIt() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        ConnectorSyncWorker worker = mock(ConnectorSyncWorker.class);
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        when(provider.type()).thenReturn("OBSIDIAN");
        var lease = connectorLease();
        var definition = new SourceConnectorDefinition(
                "notes", new KnowledgeSpaceId("engineering"), "OBSIDIAN",
                "Notes", 70, Map.of()
        );
        when(state.claimAvailable(any(), eq(2), eq(NOW.plusSeconds(120)), eq(NOW)))
                .thenReturn(List.of(lease));
        when(state.findActive(lease.tenantId(), lease.connectorId()))
                .thenReturn(java.util.Optional.of(definition));
        var coordinator = new ConnectorRunCoordinator(
                state, List.of(provider), Runnable::run, worker,
                properties(), Clock.fixed(NOW, ZoneOffset.UTC)
        );

        coordinator.recover();

        verify(worker).synchronize(lease, definition, provider);
    }

    @Test
    void evaluationRecoveryUsesPersistedCaseSnapshot() {
        EvaluationStore store = mock(EvaluationStore.class);
        EvaluationRunWorker worker = mock(EvaluationRunWorker.class);
        var lease = evaluationLease();
        var value = new EvaluationStore.Case(
                lease.caseIds().getFirst(), lease.datasetId(), "query", Set.of(),
                Set.of(UUID.randomUUID()), Set.of(), 8, Map.of(), NOW
        );
        when(store.claimAvailable(any(), eq(2), eq(NOW.plusSeconds(120)), eq(NOW)))
                .thenReturn(List.of(lease));
        when(store.cases(lease.tenantId(), lease.datasetId())).thenReturn(List.of(value));
        var coordinator = new EvaluationRunCoordinator(
                store, Runnable::run, worker, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        coordinator.recover();

        verify(worker).execute(lease, List.of(value));
    }

    @Test
    void connectorSubmissionFailureTerminatesClaimBeforeReturningSaturation() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        ConnectorSyncWorker worker = mock(ConnectorSyncWorker.class);
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        when(provider.type()).thenReturn("OBSIDIAN");
        var lease = connectorLease();
        var definition = new SourceConnectorDefinition(
                "notes", new KnowledgeSpaceId("engineering"), "OBSIDIAN",
                "Notes", 70, Map.of()
        );
        when(state.claim(eq(lease.tenantId()), eq(lease.runId()), any(),
                eq(NOW.plusSeconds(120)), eq(NOW)))
                .thenReturn(java.util.Optional.of(lease));
        when(state.findActive(lease.tenantId(), lease.connectorId()))
                .thenReturn(java.util.Optional.of(definition));
        var coordinator = new ConnectorRunCoordinator(
                state,
                List.of(provider),
                command -> { throw new RejectedExecutionException("full"); },
                worker,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertFalse(coordinator.submit(lease.tenantId(), lease.runId()));

        verify(worker).fail(lease, "WORK_QUEUE_SATURATED");
    }

    @Test
    void evaluationSubmissionFailureTerminatesClaimBeforeReturningSaturation() {
        EvaluationStore store = mock(EvaluationStore.class);
        EvaluationRunWorker worker = mock(EvaluationRunWorker.class);
        var lease = evaluationLease();
        var value = new EvaluationStore.Case(
                lease.caseIds().getFirst(), lease.datasetId(), "query", Set.of(),
                Set.of(UUID.randomUUID()), Set.of(), 8, Map.of(), NOW
        );
        when(store.claim(eq(lease.tenantId()), eq(lease.runId()), any(),
                eq(NOW.plusSeconds(120)), eq(NOW)))
                .thenReturn(java.util.Optional.of(lease));
        when(store.cases(lease.tenantId(), lease.datasetId())).thenReturn(List.of(value));
        var coordinator = new EvaluationRunCoordinator(
                store,
                command -> { throw new RejectedExecutionException("full"); },
                worker,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertFalse(coordinator.submit(lease.tenantId(), lease.runId()));

        verify(worker).fail(lease, "WORK_QUEUE_SATURATED");
    }

    private static ConnectorStateStore.SynchronizationLease connectorLease() {
        PrincipalContext principal = principal();
        return new ConnectorStateStore.SynchronizationLease(
                UUID.randomUUID(), principal, "notes", UUID.randomUUID(),
                ConnectorCursor.initial(), 0, 0, "old-worker", 2,
                NOW.plusSeconds(120)
        );
    }

    private static EvaluationStore.WorkLease evaluationLease() {
        PrincipalContext principal = principal();
        return new EvaluationStore.WorkLease(
                principal.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID()), Map.of("topK", 8), principal,
                "old-worker", 2, NOW.plusSeconds(120)
        );
    }

    private static PrincipalContext principal() {
        return new PrincipalContext(
                new TenantId("tenant-a"), new PrincipalId("admin"),
                Set.of("knowledge-admin"), Set.of("engineering"), false
        );
    }

    private static AsyncRunProperties properties() {
        return new AsyncRunProperties(
                Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofSeconds(10), 2
        );
    }
}
