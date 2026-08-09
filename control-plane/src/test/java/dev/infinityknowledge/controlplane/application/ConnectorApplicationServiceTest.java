package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.ObsidianConnectorRequest;
import dev.infinityknowledge.controlplane.config.ConnectorProperties;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @TempDir
    private Path vault;

    @Test
    void delegatesConfigurationWithoutUsingJdbc() throws Exception {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        SourceConnectorProvider provider = provider();
        when(state.configure(any())).thenReturn(true);
        var service = service(state, provider);

        service.configureObsidian(admin(), new ObsidianConnectorRequest(
                "notes",
                "engineering",
                "Engineering notes",
                "Knowledge",
                vault.toString(),
                75
        ));

        var expected = new ConnectorStateStore.ConnectorRegistration(
                admin().tenantId(),
                "notes",
                new KnowledgeSpaceId("engineering"),
                "OBSIDIAN",
                "Engineering notes",
                75,
                Map.of(
                        "vaultName", "Knowledge",
                        "vaultPath", vault.toRealPath().toString()
                ),
                NOW
        );
        verify(state).configure(expected);
    }

    @Test
    void opensRegisteredProviderAndCompletesSynchronization() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        SourceConnectorProvider provider = provider();
        SourceConnector connector = mock(SourceConnector.class);
        SourceConnectorDefinition definition = definition();
        when(state.findActive(admin().tenantId(), "notes"))
                .thenReturn(Optional.of(definition));
        when(state.tryStart(any())).thenReturn(true);
        when(provider.open(definition)).thenReturn(connector);
        when(connector.pull(
                admin().tenantId(),
                definition.spaceId(),
                ConnectorCursor.initial(),
                25
        )).thenReturn(new ConnectorBatch(
                List.of(),
                ConnectorCursor.initial(),
                false
        ));
        var service = service(state, provider);

        var response = service.synchronize(admin(), "notes");

        assertEquals("RUNNING", response.status());
        verify(provider).open(definition);
        verify(state).saveCheckpoint(any());
        verify(state).complete(
                admin().tenantId(),
                response.runId(),
                0,
                0,
                NOW
        );
    }

    @Test
    void rejectsConcurrentRunBeforeOpeningProvider() {
        ConnectorStateStore state = mock(ConnectorStateStore.class);
        SourceConnectorProvider provider = provider();
        when(state.findActive(admin().tenantId(), "notes"))
                .thenReturn(Optional.of(definition()));
        when(state.tryStart(any())).thenReturn(false);
        var service = service(state, provider);

        assertThrows(
                IllegalStateException.class,
                () -> service.synchronize(admin(), "notes")
        );

        verify(provider, never()).open(any());
    }

    private ConnectorApplicationService service(
            ConnectorStateStore state,
            SourceConnectorProvider provider
    ) {
        var ingestion = mock(MarkdownIngestionService.class);
        var properties = new ConnectorProperties(
                vault.toString(),
                25,
                1_048_576L
        );
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new ConnectorApplicationService(
                state,
                List.of(provider),
                properties,
                Runnable::run,
                clock,
                new ConnectorSyncWorker(
                        state,
                        ingestion,
                        properties,
                        clock
                )
        );
    }

    private SourceConnectorProvider provider() {
        SourceConnectorProvider provider = mock(SourceConnectorProvider.class);
        when(provider.type()).thenReturn("OBSIDIAN");
        return provider;
    }

    private SourceConnectorDefinition definition() {
        return new SourceConnectorDefinition(
                "notes",
                new KnowledgeSpaceId("engineering"),
                "OBSIDIAN",
                "Engineering notes",
                75,
                Map.of("vaultName", "Knowledge", "vaultPath", vault.toString())
        );
    }

    private PrincipalContext admin() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }
}
