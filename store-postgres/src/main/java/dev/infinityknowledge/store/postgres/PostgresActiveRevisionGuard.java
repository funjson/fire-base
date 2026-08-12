package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL-backed active-revision guard using {@code knowledge_document} as authority.
 */
public final class PostgresActiveRevisionGuard implements ActiveRevisionGuard {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Creates the guard.
     */
    public PostgresActiveRevisionGuard(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public boolean isActive(TenantId tenantId, DocumentId documentId, UUID revisionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM knowledge_document
                    WHERE tenant_id = :tenantId
                      AND id = :documentId
                      AND active_revision_id = :revisionId
                      AND status = 'ACTIVE'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId.value())
                        .addValue("documentId", documentId.value())
                        .addValue("revisionId", revisionId),
                Boolean.class
        );
        return Boolean.TRUE.equals(active);
    }

    @Override
    public List<RetrievalCandidate> retainActive(
            TenantId tenantId,
            List<RetrievalCandidate> candidates
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        candidates = List.copyOf(
                Objects.requireNonNull(candidates, "candidates must not be null")
        );
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.stream().anyMatch(candidate -> !tenantId.equals(candidate.tenantId()))) {
            throw new SecurityException("active revision check received a cross-tenant candidate");
        }
        List<UUID> documentIds = candidates.stream()
                .map(candidate -> candidate.documentId().value())
                .distinct()
                .toList();
        Map<UUID, ActiveHead> activeHeads = new HashMap<>();
        jdbc.query("""
                SELECT id, space_id, active_revision_id
                FROM knowledge_document
                WHERE tenant_id = :tenantId
                  AND status = 'ACTIVE'
                  AND id IN (:documentIds)
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId.value())
                        .addValue("documentIds", documentIds),
                (RowCallbackHandler) resultSet -> activeHeads.put(
                        resultSet.getObject("id", UUID.class),
                        new ActiveHead(
                                new KnowledgeSpaceId(resultSet.getString("space_id")),
                                resultSet.getObject("active_revision_id", UUID.class)
                        )
                )
        );
        return candidates.stream()
                .filter(candidate -> {
                    ActiveHead head = activeHeads.get(candidate.documentId().value());
                    return head != null
                            && head.spaceId().equals(candidate.spaceId())
                            && head.revisionId().equals(candidate.revisionId());
                })
                .toList();
    }

    private record ActiveHead(KnowledgeSpaceId spaceId, UUID revisionId) {
    }
}
