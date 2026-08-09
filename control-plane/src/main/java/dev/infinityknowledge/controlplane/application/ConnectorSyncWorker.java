package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.config.ConnectorProperties;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import dev.infinityknowledge.spi.connector.SourceConnectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Executes one connector synchronization after the application service acquires single-flight.
 */
@Component
public final class ConnectorSyncWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            ConnectorSyncWorker.class
    );

    private final ConnectorStateStore state;
    private final MarkdownIngestionService ingestion;
    private final ConnectorProperties properties;
    private final Clock clock;

    public ConnectorSyncWorker(
            ConnectorStateStore state,
            MarkdownIngestionService ingestion,
            ConnectorProperties properties,
            Clock clock
    ) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Pulls bounded pages, persists checkpoints and records a terminal run state.
     */
    public void synchronize(
            PrincipalContext principal,
            SourceConnectorDefinition definition,
            SourceConnectorProvider provider,
            UUID runId,
            UUID snapshotId
    ) {
        long seen = 0;
        long changed = 0;
        try {
            var connector = provider.open(definition);
            ConnectorCursor cursor = ConnectorCursor.initial();
            boolean hasMore;
            do {
                var batch = connector.pull(
                        principal.tenantId(),
                        definition.spaceId(),
                        cursor,
                        properties.batchSize()
                );
                for (var record : batch.records()) {
                    var result = ingestion.ingestSourceRecord(
                            principal,
                            definition.spaceId(),
                            record,
                            definition.authority()
                    );
                    seen++;
                    if (result.changed()) {
                        changed++;
                    }
                }
                cursor = batch.nextCursor();
                hasMore = batch.hasMore();
                state.saveCheckpoint(new ConnectorStateStore.ConnectorCheckpoint(
                        principal.tenantId(),
                        definition.connectorId(),
                        cursor,
                        snapshotId,
                        clock.instant()
                ));
            } while (hasMore);
            state.complete(
                    principal.tenantId(),
                    runId,
                    seen,
                    changed,
                    clock.instant()
            );
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Connector synchronization failed: runId={}, tenantId={}, "
                            + "connectorId={}, connectorType={}",
                    runId,
                    principal.tenantId().value(),
                    definition.connectorId(),
                    definition.type(),
                    failure
            );
            fail(principal, runId, "CONNECTOR_SYNC_FAILED");
        }
    }

    /**
     * Persists a stable terminal failure code.
     */
    public void fail(
            PrincipalContext principal,
            UUID runId,
            String errorCode
    ) {
        state.fail(
                principal.tenantId(),
                runId,
                errorCode,
                clock.instant()
        );
    }
}
