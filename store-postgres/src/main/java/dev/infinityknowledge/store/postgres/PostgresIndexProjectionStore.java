package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL source of truth for index generations and projection state.
 */
public final class PostgresIndexProjectionStore implements IndexProjectionStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * Creates the store.
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
            String generation,
            String normalizerVersion,
            String chunkerVersion,
            Instant now
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(embeddingSpec, "embeddingSpec must not be null");
        Objects.requireNonNull(now, "now must not be null");
        String configurationHash = configurationHash(
                embeddingSpec,
                generation,
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
                        "active index generation differs from configured embedding contract"
                );
            }
            return row.id();
        });
        if (result == null) {
            throw new IllegalStateException("index generation transaction returned no result");
        }
        return result;
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

    private static String configurationHash(
            EmbeddingSpec embeddingSpec,
            String generation,
            String normalizerVersion,
            String chunkerVersion
    ) {
        String value = String.join(
                "\u001F",
                embeddingSpec.providerId(),
                embeddingSpec.modelId(),
                Integer.toString(embeddingSpec.dimensions()),
                Objects.requireNonNull(generation, "generation must not be null"),
                Objects.requireNonNull(normalizerVersion, "normalizerVersion must not be null"),
                Objects.requireNonNull(chunkerVersion, "chunkerVersion must not be null")
        );
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private record GenerationRow(UUID id, String configurationHash) {
    }
}
