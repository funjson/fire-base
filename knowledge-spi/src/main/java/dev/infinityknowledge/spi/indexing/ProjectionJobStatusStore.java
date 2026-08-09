package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Tenant-scoped operational access to projection jobs.
 */
public interface ProjectionJobStatusStore {

    /**
     * Lists projection channels for the document's current active revision without
     * exposing payload content.
     */
    List<ProjectionJobState> findByDocument(TenantId tenantId, DocumentId documentId);

    /**
     * Requeues a dead-lettered projection for the current active revision. Returns false
     * when no matching dead job exists.
     */
    boolean requeueDead(
            TenantId tenantId,
            DocumentId documentId,
            ProjectionType projectionType,
            Instant now
    );

    /**
     * Requeues active revisions in one space for the currently configured channels.
     *
     * @return number of jobs inserted or reset to pending
     */
    int rebuildSpace(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            Set<ProjectionType> projectionTypes,
            Instant now
    );
}
