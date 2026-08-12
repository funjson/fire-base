package dev.infinityknowledge.spi.connector;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.List;
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
     * Claims one specific queued or expired run using a monotonic fencing token.
     */
    Optional<SynchronizationLease> claim(
            TenantId tenantId,
            UUID runId,
            String leaseOwner,
            Instant leaseUntil,
            Instant now
    );

    /**
     * Claims a bounded recovery batch. Concurrent instances are isolated by row locks.
     */
    List<SynchronizationLease> claimAvailable(
            String leaseOwner,
            int limit,
            Instant leaseUntil,
            Instant now
    );

    /**
     * Reads one synchronization run within the tenant boundary.
     */
    Optional<SynchronizationStatus> findRun(TenantId tenantId, UUID runId);

    /**
     * Saves the latest durable cursor for the connector.
     */
    boolean saveCheckpoint(
            SynchronizationLease lease,
            ConnectorCheckpoint checkpoint,
            long recordsSeen,
            long recordsChanged,
            Instant now
    );

    /**
     * Restarts an interrupted full snapshot from its initial cursor.
     *
     * <p>Connectors whose cursor is an offset into an in-memory source listing cannot safely
     * resume after another process reconstructs that listing. This operation atomically clears
     * the interrupted snapshot's staged manifest, resets its checkpoint and counters, and is
     * accepted only for the current unexpired fenced lease.</p>
     *
     * @return {@code false} when the lease is no longer owned by the caller
     */
    boolean restartFullSnapshot(
            SynchronizationLease lease,
            Instant now
    );

    /**
     * Stages the records observed in one full-snapshot page.
     *
     * <p>Staged rows are not authoritative. They are promoted only by
     * {@link #completeFullSnapshot} while the caller still owns the fenced lease,
     * so a failed or abandoned partial scan can never remove documents.</p>
     *
     * @return {@code false} when the lease is no longer owned by the caller
     */
    boolean stageManifest(
            SynchronizationLease lease,
            KnowledgeSpaceId spaceId,
            List<ManifestEntry> entries,
            Instant now
    );

    /** Renews a live lease and rejects stale workers. */
    boolean heartbeat(
            SynchronizationLease lease,
            Instant leaseUntil,
            Instant now
    );

    /**
     * Marks a run as successful.
     */
    boolean complete(
            SynchronizationLease lease,
            long recordsSeen,
            long recordsChanged,
            Instant completedAt
    );

    /**
     * Atomically reconciles and completes a full snapshot.
     *
     * <p>Entries missing from the complete staged snapshot are archived before
     * the new manifest becomes authoritative. The operation is fenced by the
     * run lease and therefore returns empty for stale workers.</p>
     */
    Optional<SnapshotCompletion> completeFullSnapshot(
            SynchronizationLease lease,
            KnowledgeSpaceId spaceId,
            long recordsSeen,
            long recordsChanged,
            Instant completedAt
    );

    /**
     * Marks a run as failed.
     */
    boolean fail(
            SynchronizationLease lease,
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
            PrincipalContext principal,
            String connectorId,
            UUID snapshotId,
            Instant startedAt
    ) {
        public SynchronizationRun {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(principal, "principal must not be null");
            connectorId = requireText(connectorId, "connectorId");
            Objects.requireNonNull(snapshotId, "snapshotId must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
        }

        public TenantId tenantId() {
            return principal.tenantId();
        }
    }

    /** Immutable ownership proof returned by a successful database claim. */
    record SynchronizationLease(
            UUID runId,
            PrincipalContext principal,
            String connectorId,
            UUID snapshotId,
            ConnectorCursor cursor,
            long recordsSeen,
            long recordsChanged,
            String leaseOwner,
            long leaseToken,
            Instant leaseUntil
    ) {
        public SynchronizationLease {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(principal, "principal must not be null");
            connectorId = requireText(connectorId, "connectorId");
            Objects.requireNonNull(snapshotId, "snapshotId must not be null");
            Objects.requireNonNull(cursor, "cursor must not be null");
            if (recordsSeen < 0 || recordsChanged < 0 || recordsChanged > recordsSeen) {
                throw new IllegalArgumentException("connector lease counts are invalid");
            }
            leaseOwner = requireText(leaseOwner, "leaseOwner");
            if (leaseToken < 1) {
                throw new IllegalArgumentException("leaseToken must be positive");
            }
            Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        }

        public TenantId tenantId() {
            return principal.tenantId();
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

    /** One source identity observed during a connector full snapshot. */
    record ManifestEntry(
            String externalId,
            String sourceUri,
            String contentHash,
            UUID documentId
    ) {
        public ManifestEntry {
            externalId = requireBoundedText(externalId, "externalId", 512);
            sourceUri = requireBoundedText(sourceUri, "sourceUri", 2048);
            contentHash = requireBoundedText(contentHash, "contentHash", 128);
            Objects.requireNonNull(documentId, "documentId must not be null");
        }
    }

    /** Result of an atomic full-snapshot reconciliation. */
    record SnapshotCompletion(long recordsDeleted) {
        public SnapshotCompletion {
            if (recordsDeleted < 0) {
                throw new IllegalArgumentException("recordsDeleted must not be negative");
            }
        }
    }

    record SynchronizationStatus(
            UUID runId,
            String connectorId,
            String status,
            long recordsSeen,
            long recordsChanged,
            long recordsDeleted,
            String errorCode,
            Instant startedAt,
            Instant completedAt
    ) {
        public SynchronizationStatus {
            Objects.requireNonNull(runId, "runId must not be null");
            connectorId = requireText(connectorId, "connectorId");
            status = requireText(status, "status");
            if (recordsSeen < 0 || recordsChanged < 0 || recordsChanged > recordsSeen
                    || recordsDeleted < 0) {
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

    private static String requireBoundedText(
            String value,
            String field,
            int maximumLength
    ) {
        String normalized = requireText(value, field);
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maximumLength + " characters"
            );
        }
        return normalized;
    }
}
