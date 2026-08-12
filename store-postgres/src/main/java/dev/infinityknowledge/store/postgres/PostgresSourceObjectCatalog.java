package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.SourceObjectReference;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.objectstorage.DocumentSourceObject;
import dev.infinityknowledge.spi.objectstorage.SourceObjectCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL catalog for the active revision's retained original source object. */
public final class PostgresSourceObjectCatalog implements SourceObjectCatalog {

    private final JdbcTemplate jdbc;

    /** Creates the tenant-scoped source-object catalog. */
    public PostgresSourceObjectCatalog(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public Optional<DocumentSourceObject> findActive(TenantId tenantId, DocumentId documentId) {
        return find(tenantId, documentId, true);
    }

    @Override
    public Optional<DocumentSourceObject> findRetained(
            TenantId tenantId,
            DocumentId documentId
    ) {
        return find(tenantId, documentId, false);
    }

    private Optional<DocumentSourceObject> find(
            TenantId tenantId,
            DocumentId documentId,
            boolean activeOnly
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        return jdbc.query("""
                SELECT d.space_id, d.status, s.revision_id, s.storage_id,
                       s.original_file_name, s.media_type, s.content_length,
                       s.checksum_sha256, s.stored_at
                  FROM knowledge_document d
                  JOIN document_source_object s
                    ON s.tenant_id = d.tenant_id
                   AND s.revision_id = d.active_revision_id
                 WHERE d.tenant_id = ?
                   AND d.id = ?
                   AND (? = false OR d.status = 'ACTIVE')
                """, result -> {
            if (!result.next()) {
                return Optional.empty();
            }
            var reference = new SourceObjectReference(
                    result.getObject("revision_id", java.util.UUID.class),
                    result.getString("storage_id"),
                    result.getString("original_file_name"),
                    result.getString("media_type"),
                    result.getLong("content_length"),
                    result.getString("checksum_sha256"),
                    result.getObject("stored_at", OffsetDateTime.class).toInstant()
            );
            return Optional.of(new DocumentSourceObject(
                    documentId,
                    new KnowledgeSpaceId(result.getString("space_id")),
                    DocumentStatus.valueOf(result.getString("status")),
                    reference
            ));
        }, tenantId.value(), documentId.value(), activeOnly);
    }
}
