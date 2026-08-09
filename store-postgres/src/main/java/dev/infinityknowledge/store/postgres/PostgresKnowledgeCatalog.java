package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 PostgreSQL 查询文档修订目录。
 */
public final class PostgresKnowledgeCatalog implements KnowledgeCatalog {

    private final JdbcTemplate jdbc;

    /**
     * 创建目录查询适配器。
     *
     * @param jdbc JDBC 模板
     */
    public PostgresKnowledgeCatalog(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public Optional<DocumentId> findDocumentId(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String connectorId,
            String externalId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(connectorId, "connectorId must not be null");
        Objects.requireNonNull(externalId, "externalId must not be null");
        List<UUID> matches = jdbc.queryForList("""
                SELECT id
                FROM knowledge_document
                WHERE tenant_id = ? AND space_id = ?
                  AND connector_id = ? AND external_id = ?
                """, UUID.class,
                tenantId.value(), spaceId.value(), connectorId, externalId);
        if (matches.size() > 1) {
            throw new IllegalStateException("source identity resolved multiple documents");
        }
        return matches.stream().findFirst().map(DocumentId::new);
    }
}
