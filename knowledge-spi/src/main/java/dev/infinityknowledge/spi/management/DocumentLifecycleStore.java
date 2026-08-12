package dev.infinityknowledge.spi.management;

import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Performs tenant-bound, optimistic document lifecycle transitions.
 */
public interface DocumentLifecycleStore {

    /**
     * Changes one document status when its aggregate version still matches.
     *
     * @return empty when the tenant-bound document does not exist
     * @throws DocumentLifecycleConflictException for a stale version or illegal transition
     */
    Optional<DocumentState> transition(
            TenantId tenantId,
            UUID documentId,
            long expectedVersion,
            DocumentStatus targetStatus,
            Instant occurredAt
    );

    /** Safe management view returned after a transition or idempotent no-op. */
    record DocumentState(
            UUID documentId,
            String spaceId,
            DocumentStatus status,
            long version,
            Instant updatedAt,
            boolean changed
    ) {
        public DocumentState {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
        }
    }
}
