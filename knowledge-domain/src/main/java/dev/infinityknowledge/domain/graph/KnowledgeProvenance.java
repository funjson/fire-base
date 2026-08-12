package dev.infinityknowledge.domain.graph;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable source location supporting every extracted graph assertion.
 *
 * @param id stable extraction assertion identifier
 * @param tenantId tenant owning the source
 * @param spaceId knowledge space owning the source
 * @param documentId source document
 * @param revisionId immutable source revision
 * @param chunkId exact source chunk
 * @param sourceUri human-readable original location
 * @param excerpt minimal text supporting the assertion
 * @param confidence extraction confidence between zero and one
 */
public record KnowledgeProvenance(
        UUID id,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        DocumentId documentId,
        UUID revisionId,
        UUID chunkId,
        String sourceUri,
        String excerpt,
        double confidence
) {

    /**
     * Rejects unscoped or untraceable graph assertions.
     */
    public KnowledgeProvenance {
        Objects.requireNonNull(id, "provenance id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        sourceUri = DomainChecks.requiredText(sourceUri, "sourceUri", 2_048);
        excerpt = DomainChecks.requiredText(excerpt, "excerpt", 20_000);
        confidence = DomainChecks.unitScore(confidence, "provenance confidence");
    }
}
