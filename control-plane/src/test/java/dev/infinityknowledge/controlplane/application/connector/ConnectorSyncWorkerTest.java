package dev.infinityknowledge.controlplane.application.connector;

import dev.infinityknowledge.controlplane.api.document.MarkdownDocumentResponse;
import dev.infinityknowledge.controlplane.application.ingestion.MarkdownIngestionService;
import dev.infinityknowledge.controlplane.config.AsyncRunProperties;
import dev.infinityknowledge.controlplane.config.ConnectorProperties;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.connector.ConnectorBatch;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnector;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import dev.infinityknowledge.spi.connector.SourceConnectorProvider;
import dev.infinityknowledge.spi.connector.SourceRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorSyncWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    void restartsRecoveredObsidianSnapshotEvenWhenCursorIsInitial() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        MarkdownIngestionService ingestion = mock(MarkdownIngestionService.class);
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        SourceConnector connector = mock(SourceConnector.class);
        var lease = recoveredAtInitialCursorLease();
        var definition = definition();
        when(provider.open(definition)).thenReturn(connector);
        when(state.restartFullSnapshot(lease, NOW)).thenReturn(true);
        when(state.heartbeat(any(), any(), any())).thenReturn(true);
        when(connector.pull(
                lease.tenantId(), definition.spaceId(), ConnectorCursor.initial(), 25
        )).thenReturn(new ConnectorBatch(
                List.of(), ConnectorCursor.initial(), false
        ));
        when(state.stageManifest(any(), any(), any(), any())).thenReturn(true);
        when(state.saveCheckpoint(any(), any(), any(Long.class), any(Long.class), any()))
                .thenReturn(true);
        when(state.completeFullSnapshot(any(), any(), any(Long.class), any(Long.class), any()))
                .thenReturn(Optional.of(new ConnectorStateStore.SnapshotCompletion(0)));

        worker(state, ingestion).synchronize(lease, definition, provider);

        verify(state).restartFullSnapshot(lease, NOW);
        verify(connector).pull(
                lease.tenantId(), definition.spaceId(), ConnectorCursor.initial(), 25
        );
        verify(state).stageManifest(lease, definition.spaceId(), List.of(), NOW);
        verify(state).saveCheckpoint(
                lease,
                new ConnectorStateStore.ConnectorCheckpoint(
                        lease.tenantId(),
                        definition.connectorId(),
                        ConnectorCursor.initial(),
                        lease.snapshotId(),
                        NOW
                ),
                0,
                0,
                NOW
        );
        verify(state).completeFullSnapshot(lease, definition.spaceId(), 0, 0, NOW);
        verify(state, never()).complete(any(), any(Long.class), any(Long.class), any());
        verify(state, never()).fail(any(), any(), any());
    }

    @Test
    void stopsBeforeOpeningConnectorWhenSnapshotRestartIsFenced() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        var lease = recoveredLease();
        when(state.restartFullSnapshot(lease, NOW)).thenReturn(false);

        worker(state, mock(MarkdownIngestionService.class))
                .synchronize(lease, definition(), provider);

        verify(provider, never()).open(any());
        verify(state, never()).heartbeat(any(), any(), any());
        verify(state, never()).saveCheckpoint(any(), any(), any(Long.class), any(Long.class), any());
        verify(state, never()).completeFullSnapshot(
                any(), any(), any(Long.class), any(Long.class), any()
        );
        verify(state, never()).fail(any(), any(), any());
    }

    @Test
    void stopsWithoutTerminalWriteAfterLeaseLoss() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        when(provider.open(any())).thenReturn(mock(SourceConnector.class));
        when(state.heartbeat(any(), any(), any())).thenReturn(false);

        worker(state, mock(MarkdownIngestionService.class))
                .synchronize(lease(), definition(), provider);

        verify(state, never()).saveCheckpoint(any(), any(), any(Long.class), any(Long.class), any());
        verify(state, never()).complete(any(), any(Long.class), any(Long.class), any());
        verify(state, never()).fail(any(), any(), any());
    }

    @Test
    void stopsBeforeNextRecordWhenLeaseIsLostInsideBatch() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        MarkdownIngestionService ingestion = mock(MarkdownIngestionService.class);
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        SourceConnector connector = mock(SourceConnector.class);
        var lease = lease();
        var definition = definition();
        var first = record("first.md");
        var second = record("second.md");
        when(provider.open(definition)).thenReturn(connector);
        when(state.heartbeat(any(), any(), any())).thenReturn(true, true, false);
        when(connector.pull(
                lease.tenantId(), definition.spaceId(), lease.cursor(), 25
        )).thenReturn(new ConnectorBatch(
                List.of(first, second), ConnectorCursor.initial(), false
        ));
        when(ingestion.ingestSourceRecord(
                lease, definition.spaceId(), first, definition.authority()
        )).thenReturn(new MarkdownDocumentResponse(
                UUID.randomUUID(), UUID.randomUUID(), true, 1, 1, "QUEUED", List.of()
        ));

        worker(state, ingestion).synchronize(lease, definition, provider);

        verify(ingestion).ingestSourceRecord(
                lease, definition.spaceId(), first, definition.authority()
        );
        verify(ingestion, never()).ingestSourceRecord(
                lease, definition.spaceId(), second, definition.authority()
        );
        verify(state, never()).stageManifest(any(), any(), any(), any());
        verify(state, never()).saveCheckpoint(any(), any(), any(Long.class), any(Long.class), any());
        verify(state, never()).completeFullSnapshot(
                any(), any(), any(Long.class), any(Long.class), any()
        );
        verify(state, never()).fail(any(), any(), any());
    }

    @Test
    void neverReconcilesAManifestWhenPageProcessingFails() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        MarkdownIngestionService ingestion = mock(MarkdownIngestionService.class);
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        SourceConnector connector = mock(SourceConnector.class);
        var lease = lease();
        var definition = definition();
        var record = record("guide.md");
        when(provider.open(definition)).thenReturn(connector);
        when(state.heartbeat(any(), any(), any())).thenReturn(true);
        when(connector.pull(
                lease.tenantId(), definition.spaceId(), lease.cursor(), 25
        )).thenReturn(new ConnectorBatch(
                List.of(record), ConnectorCursor.initial(), false
        ));
        when(ingestion.ingestSourceRecord(any(), any(), any(), any(Integer.class)))
                .thenThrow(new IllegalStateException("parser failed"));
        when(state.fail(any(), any(), any())).thenReturn(true);

        worker(state, ingestion).synchronize(lease, definition, provider);

        verify(state, never()).stageManifest(any(), any(), any(), any());
        verify(state, never()).completeFullSnapshot(
                any(), any(), any(Long.class), any(Long.class), any()
        );
        verify(state).fail(lease, "CONNECTOR_SYNC_FAILED", NOW);
    }

    @Test
    void explicitTombstoneIsNotPromotedIntoTheFullSnapshot() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        MarkdownIngestionService ingestion = mock(MarkdownIngestionService.class);
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        SourceConnector connector = mock(SourceConnector.class);
        var lease = lease();
        var definition = definition();
        var source = record("removed.md");
        var tombstone = new SourceRecord(
                source.source(), source.title(), source.mediaType(), "",
                source.contentHash(), source.metadata(), source.modifiedAt(), true
        );
        when(provider.open(definition)).thenReturn(connector);
        when(state.heartbeat(any(), any(), any())).thenReturn(true);
        when(connector.pull(
                lease.tenantId(), definition.spaceId(), lease.cursor(), 25
        )).thenReturn(new ConnectorBatch(
                List.of(tombstone), ConnectorCursor.initial(), false
        ));
        when(state.stageManifest(any(), any(), any(), any())).thenReturn(true);
        when(state.saveCheckpoint(any(), any(), any(Long.class), any(Long.class), any()))
                .thenReturn(true);
        when(state.completeFullSnapshot(any(), any(), any(Long.class), any(Long.class), any()))
                .thenReturn(Optional.of(new ConnectorStateStore.SnapshotCompletion(1)));

        worker(state, ingestion).synchronize(lease, definition, provider);

        verify(ingestion, never()).ingestSourceRecord(any(), any(), any(), any(Integer.class));
        verify(state).stageManifest(lease, definition.spaceId(), List.of(), NOW);
        verify(state).completeFullSnapshot(lease, definition.spaceId(), 1, 0, NOW);
    }

    private static ConnectorSyncWorker worker(
            ConnectorStateStore state,
            MarkdownIngestionService ingestion
    ) {
        return new ConnectorSyncWorker(
                state,
                ingestion,
                new ConnectorProperties("", 25, 1_048_576),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static ConnectorStateStore.SynchronizationLease lease() {
        return new ConnectorStateStore.SynchronizationLease(
                UUID.randomUUID(),
                new PrincipalContext(
                        new TenantId("tenant-a"),
                        new PrincipalId("admin"),
                        Set.of("knowledge-admin"),
                        Set.of("engineering"),
                        false
                ),
                "notes",
                UUID.randomUUID(),
                ConnectorCursor.initial(),
                0,
                0,
                "worker-a",
                1,
                NOW.plusSeconds(120)
        );
    }

    private static ConnectorStateStore.SynchronizationLease recoveredLease() {
        var fresh = lease();
        return new ConnectorStateStore.SynchronizationLease(
                fresh.runId(),
                fresh.principal(),
                fresh.connectorId(),
                fresh.snapshotId(),
                new ConnectorCursor(Map.of("offset", "25")),
                3,
                1,
                fresh.leaseOwner(),
                2,
                fresh.leaseUntil()
        );
    }

    private static ConnectorStateStore.SynchronizationLease recoveredAtInitialCursorLease() {
        var fresh = lease();
        return new ConnectorStateStore.SynchronizationLease(
                fresh.runId(),
                fresh.principal(),
                fresh.connectorId(),
                fresh.snapshotId(),
                ConnectorCursor.initial(),
                0,
                0,
                fresh.leaseOwner(),
                2,
                fresh.leaseUntil()
        );
    }

    private static SourceConnectorDefinition definition() {
        return new SourceConnectorDefinition(
                "notes",
                new KnowledgeSpaceId("engineering"),
                "OBSIDIAN",
                "Notes",
                70,
                Map.of()
        );
    }

    private static SourceRecord record(String externalId) {
        return new SourceRecord(
                new SourceDescriptor(
                        "notes",
                        SourceType.OBSIDIAN,
                        externalId,
                        "obsidian://open?vault=Knowledge&file=" + externalId,
                        Map.of()
                ),
                externalId,
                "text/markdown",
                "# Guide",
                "a".repeat(64),
                Map.of(),
                NOW,
                false
        );
    }

    private static AsyncRunProperties properties() {
        return new AsyncRunProperties(
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                2
        );
    }
}
