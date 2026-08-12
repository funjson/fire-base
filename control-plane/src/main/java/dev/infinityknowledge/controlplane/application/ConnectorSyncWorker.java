package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.config.ConnectorProperties;
import dev.infinityknowledge.controlplane.config.AsyncRunProperties;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import dev.infinityknowledge.spi.connector.SourceConnectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    private final AsyncRunProperties asyncProperties;
    private final Clock clock;

    public ConnectorSyncWorker(
            ConnectorStateStore state,
            MarkdownIngestionService ingestion,
            ConnectorProperties properties,
            AsyncRunProperties asyncProperties,
            Clock clock
    ) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.asyncProperties = Objects.requireNonNull(
                asyncProperties,
                "asyncProperties must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Pulls bounded pages, persists checkpoints and records a terminal run state.
     */
    public void synchronize(
            ConnectorStateStore.SynchronizationLease lease,
            SourceConnectorDefinition definition,
            SourceConnectorProvider provider
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        long seen = lease.recordsSeen();
        long changed = lease.recordsChanged();
        boolean fullSnapshot = "OBSIDIAN".equals(definition.type());
        try {
            ConnectorCursor cursor = lease.cursor();
            boolean recoveredSnapshot = fullSnapshot && (
                    lease.leaseToken() > 1
                            || !ConnectorCursor.initial().equals(cursor)
            );
            if (recoveredSnapshot) {
                Instant restartAt = clock.instant();
                if (!state.restartFullSnapshot(lease, restartAt)) {
                    LOGGER.info(
                            "Connector lease was lost before snapshot restart: "
                                    + "runId={}, tenantId={}",
                            lease.runId(), lease.tenantId().value()
                    );
                    return;
                }
                cursor = ConnectorCursor.initial();
                seen = 0;
                changed = 0;
            }
            var connector = provider.open(definition);
            boolean hasMore;
            do {
                if (!renewLease(lease, "pull")) {
                    return;
                }
                var batch = connector.pull(
                        lease.tenantId(),
                        definition.spaceId(),
                        cursor,
                        properties.batchSize()
                );
                List<ConnectorStateStore.ManifestEntry> manifestEntries = new ArrayList<>();
                for (var record : batch.records()) {
                    if (!renewLease(lease, "record processing")) {
                        return;
                    }
                    seen++;
                    if (!record.deleted()) {
                        var result = ingestion.ingestSourceRecord(
                                lease,
                                definition.spaceId(),
                                record,
                                definition.authority()
                        );
                        if (result.changed()) {
                            changed++;
                        }
                        if (fullSnapshot) {
                            manifestEntries.add(new ConnectorStateStore.ManifestEntry(
                                    record.source().externalId(),
                                    record.source().uri(),
                                    record.contentHash(),
                                    result.documentId()
                            ));
                        }
                    }
                }
                cursor = batch.nextCursor();
                hasMore = batch.hasMore();
                Instant checkpointAt = clock.instant();
                if (fullSnapshot && !state.stageManifest(
                        lease,
                        definition.spaceId(),
                        manifestEntries,
                        checkpointAt
                )) {
                    LOGGER.info(
                            "Connector lease was lost before manifest staging: "
                                    + "runId={}, tenantId={}",
                            lease.runId(), lease.tenantId().value()
                    );
                    return;
                }
                if (!state.saveCheckpoint(
                        lease,
                        new ConnectorStateStore.ConnectorCheckpoint(
                                lease.tenantId(),
                                definition.connectorId(),
                                cursor,
                                lease.snapshotId(),
                                checkpointAt
                        ), seen, changed, checkpointAt)) {
                    LOGGER.info(
                            "Connector lease was lost before checkpoint: runId={}, tenantId={}",
                            lease.runId(), lease.tenantId().value()
                    );
                    return;
                }
            } while (hasMore);
            if (fullSnapshot) {
                var completion = state.completeFullSnapshot(
                        lease,
                        definition.spaceId(),
                        seen,
                        changed,
                        clock.instant()
                );
                if (completion.isEmpty()) {
                    LOGGER.info(
                            "Connector snapshot completion was fenced: "
                                    + "runId={}, tenantId={}",
                            lease.runId(), lease.tenantId().value()
                    );
                } else {
                    long recordsDeleted = completion.orElseThrow().recordsDeleted();
                    if (recordsDeleted == 0) {
                        return;
                    }
                    LOGGER.info(
                            "Connector snapshot archived missing documents: "
                                    + "runId={}, tenantId={}, recordsDeleted={}",
                            lease.runId(),
                            lease.tenantId().value(),
                            recordsDeleted
                    );
                }
                return;
            }
            if (!state.complete(
                    lease,
                    seen,
                    changed,
                    clock.instant()
            )) {
                LOGGER.info(
                        "Connector completion was fenced: runId={}, tenantId={}",
                        lease.runId(), lease.tenantId().value()
                );
            }
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Connector synchronization failed: runId={}, tenantId={}, "
                            + "connectorId={}, connectorType={}, failureType={}",
                    lease.runId(),
                    lease.tenantId().value(),
                    definition.connectorId(),
                    definition.type(),
                    failure.getClass().getSimpleName()
            );
            fail(lease, "CONNECTOR_SYNC_FAILED");
        }
    }

    private boolean renewLease(
            ConnectorStateStore.SynchronizationLease lease,
            String stage
    ) {
        Instant now = clock.instant();
        if (state.heartbeat(
                lease,
                now.plus(asyncProperties.leaseDuration()),
                now
        )) {
            return true;
        }
        LOGGER.info(
                "Connector lease was lost before {}: runId={}, tenantId={}",
                stage,
                lease.runId(),
                lease.tenantId().value()
        );
        return false;
    }

    /**
     * Persists a stable terminal failure code.
     */
    public void fail(
            ConnectorStateStore.SynchronizationLease lease,
            String errorCode
    ) {
        if (!state.fail(
                lease,
                errorCode,
                clock.instant()
        )) {
            LOGGER.info(
                    "Connector failure was fenced: runId={}, tenantId={}",
                    lease.runId(), lease.tenantId().value()
            );
        }
    }
}
