package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists immutable index generations and per-document projection status.
 */
public interface IndexProjectionStore {

    /**
     * Returns the active generation or creates it when the space has none.
     * A mismatching active generation is rejected rather than silently mixed.
     */
    UUID resolveActiveGeneration(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            EmbeddingSpec embeddingSpec,
            String generation,
            String normalizerVersion,
            String chunkerVersion,
            Instant now
    );

    /**
     * Records one projection state while preserving the other projection channels.
     */
    default void recordProjectionStatus(
            TenantId tenantId,
            UUID generationId,
            DocumentId documentId,
            UUID revisionId,
            ProjectionType projectionType,
            ProjectionStatus status,
            Instant now
    ) {
        if (projectionType != ProjectionType.VECTOR) {
            throw new UnsupportedOperationException(
                    "projection store does not support " + projectionType
            );
        }
        recordVectorStatus(
                tenantId,
                generationId,
                documentId,
                revisionId,
                status,
                now
        );
    }

    /**
     * Compatibility convenience for vector projectors.
     */
    void recordVectorStatus(
            TenantId tenantId,
            UUID generationId,
            DocumentId documentId,
            UUID revisionId,
            ProjectionStatus status,
            Instant now
    );
}
