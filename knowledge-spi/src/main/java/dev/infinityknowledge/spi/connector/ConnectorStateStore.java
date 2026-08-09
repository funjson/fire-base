package dev.infinityknowledge.spi.connector;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists connector configuration, checkpoints and synchronization runs.
 *
 * <p>The port is intentionally grouped around the connector synchronization
 * use case. It keeps SQL and JSON persistence details out of the Control Plane
 * without introducing one repository per table.</p>
 */
public interface ConnectorStateStore {

    /**
     * Creates or updates a connector when its target space is active.
     *
     * @return {@code true} when the connector was persisted
     */
    boolean configure(ConnectorRegistration registration);

    /**
     * Finds an active connector within the tenant boundary.
     */
    Optional<SourceConnectorDefinition> findActive(
            TenantId tenantId,
            String connectorId
    );

    /**
     * Atomically starts a synchronization run.
     *
     * @return {@code false} when the same connector already has a running run
     */
    boolean tryStart(SynchronizationRun run);

    /**
     * Reads one synchronization run within the tenant boundary.
     */
    Optional<SynchronizationStatus> findRun(TenantId tenantId, UUID runId);

    /**
     * Saves the latest durable cursor for the connector.
     */
    void saveCheckpoint(ConnectorCheckpoint checkpoint);

    /**
     * Marks a run as successful.
     */
    void complete(
            TenantId tenantId,
            UUID runId,
            long recordsSeen,
            long recordsChanged,
            Instant completedAt
    );

    /**
     * Marks a run as failed.
     */
    void fail(
            TenantId tenantId,
            UUID runId,
            String errorCode,
            Instant completedAt
    );

    record ConnectorRegistration(
            TenantId tenantId,
            String connectorId,
            KnowledgeSpaceId spaceId,
            String type,
            String displayName,
            int authority,
            Map<String, String> configuration,
            Instant occurredAt
    ) {
        public ConnectorRegistration {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            connectorId = requireText(connectorId, "connectorId");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            type = requireText(type, "type").toUpperCase(java.util.Locale.ROOT);
            displayName = requireText(displayName, "displayName");
            if (authority < 0 || authority > 100) {
                throw new IllegalArgumentException("authority must be between 0 and 100");
            }
            configuration = Map.copyOf(
                    Objects.requireNonNull(configuration, "configuration must not be null")
            );
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        }
    }

    record SynchronizationRun(
            UUID runId,
            TenantId tenantId,
            String connectorId,
            UUID snapshotId,
            Instant startedAt
    ) {
        public SynchronizationRun {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            connectorId = requireText(connectorId, "connectorId");
            Objects.requireNonNull(snapshotId, "snapshotId must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
        }
    }

    record ConnectorCheckpoint(
            TenantId tenantId,
            String connectorId,
            ConnectorCursor cursor,
            UUID snapshotId,
            Instant updatedAt
    ) {
        public ConnectorCheckpoint {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            connectorId = requireText(connectorId, "connectorId");
            Objects.requireNonNull(cursor, "cursor must not be null");
            Objects.requireNonNull(snapshotId, "snapshotId must not be null");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }
    }

    record SynchronizationStatus(
            UUID runId,
            String connectorId,
            String status,
            long recordsSeen,
            long recordsChanged,
            String errorCode,
            Instant startedAt,
            Instant completedAt
    ) {
        public SynchronizationStatus {
            Objects.requireNonNull(runId, "runId must not be null");
            connectorId = requireText(connectorId, "connectorId");
            status = requireText(status, "status");
            if (recordsSeen < 0 || recordsChanged < 0 || recordsChanged > recordsSeen) {
                throw new IllegalArgumentException("connector run counts are invalid");
            }
            Objects.requireNonNull(startedAt, "startedAt must not be null");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
