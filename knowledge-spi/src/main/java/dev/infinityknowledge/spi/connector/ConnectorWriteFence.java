package dev.infinityknowledge.spi.connector;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies the connector lease that must still be owned when a knowledge revision commits.
 *
 * <p>The PostgreSQL writer validates and locks this lease in the same transaction as the
 * document write. A heartbeat before ingestion alone is not a commit fence.</p>
 */
public record ConnectorWriteFence(
        TenantId tenantId,
        UUID runId,
        String leaseOwner,
        long leaseToken
) {
    public ConnectorWriteFence {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        leaseOwner = DomainChecks.requiredText(leaseOwner, "leaseOwner", 128);
        if (leaseToken < 1) {
            throw new IllegalArgumentException("leaseToken must be positive");
        }
    }

    /** Creates the immutable write fence carried by a claimed synchronization lease. */
    public static ConnectorWriteFence from(
            ConnectorStateStore.SynchronizationLease lease
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        return new ConnectorWriteFence(
                lease.tenantId(), lease.runId(), lease.leaseOwner(), lease.leaseToken()
        );
    }
}
