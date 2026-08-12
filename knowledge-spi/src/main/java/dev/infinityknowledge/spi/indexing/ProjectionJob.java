package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;
import java.util.UUID;

/**
 * A leased, at-least-once external index projection job.
 *
 * @param id job identifier
 * @param tenantId tenant boundary
 * @param spaceId knowledge-space boundary
 * @param documentId stable document identifier
 * @param revisionId immutable source revision
 * @param projectionType target projection
 * @param attempt current one-based delivery attempt
 * @param leaseToken monotonic fencing token for the current claim
 */
public record ProjectionJob(
        UUID id,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        DocumentId documentId,
        UUID revisionId,
        ProjectionType projectionType,
        int attempt,
        long leaseToken
) {

    /**
     * Validates identity and attempt state.
     */
    public ProjectionJob {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(projectionType, "projectionType must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (leaseToken < 1) {
            throw new IllegalArgumentException("leaseToken must be positive");
        }
    }
}
