package dev.infinityknowledge.spi.objectstorage;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;

import java.util.Optional;

/** Resolves original source objects without exposing physical object-store keys. */
@FunctionalInterface
public interface SourceObjectCatalog {

    /** Returns only the current active revision's source object in the supplied tenant. */
    Optional<DocumentSourceObject> findActive(TenantId tenantId, DocumentId documentId);

    /**
     * Returns the retained current revision for lifecycle administration.
     * Adapters that do not support inactive retention remain active-only by default.
     */
    default Optional<DocumentSourceObject> findRetained(
            TenantId tenantId,
            DocumentId documentId
    ) {
        return findActive(tenantId, documentId);
    }
}
