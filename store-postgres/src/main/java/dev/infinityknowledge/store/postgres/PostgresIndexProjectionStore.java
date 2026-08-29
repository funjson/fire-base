package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ActiveIndexGeneration;
import dev.infinityknowledge.spi.indexing.ActiveIndexGenerationCatalog;
import dev.infinityknowledge.spi.indexing.IndexGenerationIdentity;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 PostgreSQL 作为索引代际与投影状态的事实来源。
 */
public final class PostgresIndexProjectionStore
        implements IndexProjectionStore, ActiveIndexGenerationCatalog {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * 创建投影状态存储实现。
     */
    public PostgresIndexProjectionStore(
            JdbcTemplate jdbc,
            TransactionTemplate transaction
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    @Override
    public UUID resolveActiveGeneration(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            EmbeddingSpec embeddingSpec,
            IndexPhysicalContract physicalContract,
            String normalizerVersion,
            String chunkerVersion,
            Instant now
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(embeddingSpec, "embeddingSpec must not be null");
        Objects.requireNonNull(now, "now must not be null");
        String configurationHash = IndexGenerationIdentity.configurationVersion(
                embeddingSpec,
                physicalContract,
                normalizerVersion,
                chunkerVersion
        );
        UUID generationId = UUID.nameUUIDFromBytes(
                (tenantId.value() + ":" + spaceId.value() + ":" + configurationHash)
                        .getBytes(StandardCharsets.UTF_8)
        );
        UUID result = transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO index_generation
                        (tenant_id, id, space_id, status, embedding_provider,
                         embedding_model, embedding_dimensions, normalizer_version,
                         chunker_version, configuration_hash, created_at, published_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    tenantId.value(),
                    generationId,
                    spaceId.value(),
                    embeddingSpec.providerId(),
                    embeddingSpec.modelId(),
                    embeddingSpec.dimensions(),
                    normalizerVersion,
                    chunkerVersion,
                    configurationHash,
                    now.atOffset(ZoneOffset.UTC),
                    now.atOffset(ZoneOffset.UTC)
            );
            List<GenerationRow> active = jdbc.query("""
                    SELECT id, configuration_hash
                    FROM index_generation
                    WHERE tenant_id = ? AND space_id = ? AND status = 'ACTIVE'
                    """,
                    (resultSet, rowNumber) -> new GenerationRow(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("configuration_hash")
                    ),
                    tenantId.value(),
                    spaceId.value()
            );
            if (active.size() != 1) {
                throw new IllegalStateException(
                        "knowledge space must have exactly one active index generation"
                );
            }
            GenerationRow row = active.getFirst();
            if (!configurationHash.equals(row.configurationHash())) {
                throw new IllegalStateException(
                        "active index generation differs from deployed index contract"
                );
            }
            return row.id();
        });
        if (result == null) {
            throw new IllegalStateException("index generation transaction returned no result");
        }
        return result;
    }

    /**
     * 从索引代际事实表读取当前活动版本，不创建代际，也不把配置文件值伪装成索引版本。
     */
    @Override
    public Optional<ActiveIndexGeneration> findActiveGeneration(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        List<ActiveIndexGeneration> active = jdbc.query("""
                SELECT id, configuration_hash
                FROM index_generation
                WHERE tenant_id = ? AND space_id = ? AND status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> new ActiveIndexGeneration(
                        spaceId,
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("configuration_hash")
                ),
                tenantId.value(),
                spaceId.value()
        );
        if (active.size() > 1) {
            throw new IllegalStateException(
                    "knowledge space has more than one active index generation"
            );
        }
        return active.stream().findFirst();
    }

    @Override
    public void recordProjectionStatus(
            TenantId tenantId,
            UUID generationId,
            DocumentId documentId,
            UUID revisionId,
            ProjectionType projectionType,
            ProjectionStatus status,
            Instant now
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(generationId, "generationId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(projectionType, "projectionType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(now, "now must not be null");
        String statusColumn = switch (projectionType) {
            case KEYWORD -> "keyword_status";
            case VECTOR -> "vector_status";
            case GRAPH -> "graph_status";
        };
        transaction.executeWithoutResult(ignored -> {
            Boolean active = jdbc.queryForObject("""
                    SELECT active_revision_id = ?
                    FROM knowledge_document
                    WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                    FOR UPDATE
                    """,
                    Boolean.class,
                    revisionId,
                    tenantId.value(),
                    documentId.value()
            );
            if (!Boolean.TRUE.equals(active)) {
                return;
            }
            jdbc.update("""
                    INSERT INTO document_index_projection
                        (tenant_id, generation_id, document_id, revision_id,
                         keyword_status, vector_status, graph_status, updated_at)
                    VALUES (?, ?, ?, ?, 'SKIPPED', 'SKIPPED', 'SKIPPED', ?)
                    ON CONFLICT (tenant_id, generation_id, document_id) DO UPDATE
                    SET revision_id = EXCLUDED.revision_id,
                        updated_at = EXCLUDED.updated_at
                    """,
                    tenantId.value(),
                    generationId,
                    documentId.value(),
                    revisionId,
                    now.atOffset(ZoneOffset.UTC)
            );
            jdbc.update("""
                    UPDATE document_index_projection
                    SET %s = ?, updated_at = ?
                    WHERE tenant_id = ? AND generation_id = ? AND document_id = ?
                      AND revision_id = ?
                    """.formatted(statusColumn),
                    status.name(),
                    now.atOffset(ZoneOffset.UTC),
                    tenantId.value(),
                    generationId,
                    documentId.value(),
                    revisionId
            );
        });
    }

    @Override
    public void recordVectorStatus(
            TenantId tenantId,
            UUID generationId,
            DocumentId documentId,
            UUID revisionId,
            ProjectionStatus status,
            Instant now
    ) {
        recordProjectionStatus(
                tenantId,
                generationId,
                documentId,
                revisionId,
                ProjectionType.VECTOR,
                status,
                now
        );
    }

    private record GenerationRow(UUID id, String configurationHash) {
    }
}
