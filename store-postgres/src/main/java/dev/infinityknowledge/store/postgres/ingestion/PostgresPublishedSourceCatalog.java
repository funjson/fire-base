package dev.infinityknowledge.store.postgres.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.PublishedSourceCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 PostgreSQL 查询正式上传的来源身份与活动原件指纹。 */
public final class PostgresPublishedSourceCatalog implements PublishedSourceCatalog {

    private final JdbcTemplate jdbc;

    /** 创建只读目录适配器。 */
    public PostgresPublishedSourceCatalog(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public Optional<PublishedSource> findByExternalId(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String connectorId,
            String externalId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(connectorId, "connectorId must not be null");
        Objects.requireNonNull(externalId, "externalId must not be null");
        return unique(jdbc.query(
                """
                SELECT d.id, r.id, r.content_hash
                  FROM knowledge_document d
                  JOIN document_revision r
                    ON r.tenant_id = d.tenant_id
                   AND r.id = d.active_revision_id
                 WHERE d.tenant_id = ?
                   AND d.space_id = ?
                   AND d.connector_id = ?
                   AND d.external_id = ?
                   AND d.status <> 'DELETED'
                 LIMIT 2
                """,
                (result, row) -> new PublishedSource(
                        new DocumentId(result.getObject(1, UUID.class)),
                        result.getObject(2, UUID.class),
                        result.getString(3)
                ),
                tenantId.value(),
                spaceId.value(),
                connectorId,
                externalId
        ), "external source identity resolved multiple documents");
    }

    @Override
    public Optional<PublishedSource> findByContentHash(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String contentHash
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256");
        }
        return unique(jdbc.query(
                """
                SELECT d.id, r.id, r.content_hash
                  FROM knowledge_document d
                  JOIN document_revision r
                    ON r.tenant_id = d.tenant_id
                   AND r.id = d.active_revision_id
                 WHERE d.tenant_id = ?
                   AND d.space_id = ?
                   AND r.content_hash = ?
                   AND d.status <> 'DELETED'
                 ORDER BY d.created_at, d.id
                 LIMIT 2
                """,
                (result, row) -> new PublishedSource(
                        new DocumentId(result.getObject(1, UUID.class)),
                        result.getObject(2, UUID.class),
                        result.getString(3)
                ),
                tenantId.value(),
                spaceId.value(),
                contentHash
        ), "content hash resolved multiple active documents");
    }

    private static Optional<PublishedSource> unique(
            List<PublishedSource> values,
            String duplicateMessage
    ) {
        if (values.size() > 1) {
            throw new IllegalStateException(duplicateMessage);
        }
        return values.stream().findFirst();
    }
}
