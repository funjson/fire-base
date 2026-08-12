package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;

import java.util.List;
import java.util.UUID;

/**
 * Uses the transactional document head to guard external-index publication and reads.
 */
public interface ActiveRevisionGuard {

    /**
     * Returns whether one immutable revision is still the active revision of its document.
     */
    boolean isActive(TenantId tenantId, DocumentId documentId, UUID revisionId);

    /**
     * Retains only candidates whose tenant, space, document and revision match the
     * current active document head.
     * Implementations must resolve the batch without issuing one query per candidate.
     */
    List<RetrievalCandidate> retainActive(
            TenantId tenantId,
            List<RetrievalCandidate> candidates
    );
}
