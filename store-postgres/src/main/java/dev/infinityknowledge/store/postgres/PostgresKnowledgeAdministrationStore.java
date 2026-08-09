package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.management.KnowledgeAdministrationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 PostgreSQL 提供管理控制台只读投影。
 */
public final class PostgresKnowledgeAdministrationStore
        implements KnowledgeAdministrationStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public PostgresKnowledgeAdministrationStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    public Overview overview(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String value = tenantId.value();
        return new Overview(
                count("knowledge_space", "status <> 'DELETED'", value),
                count("knowledge_document", "status = 'ACTIVE'", value),
                count("knowledge_chunk", "TRUE", value),
                count("connector_instance", "status <> 'DELETED'", value),
                count(
                        "projection_job",
                        "status IN ('PENDING', 'RETRY', 'RUNNING')",
                        value
                ),
                count("projection_job", "status = 'DEAD'", value),
                count("evaluation_dataset", "status <> 'ARCHIVED'", value),
                count("evaluation_run", "TRUE", value)
        );
    }

    @Override
    public List<Space> spaces(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return jdbc.query("""
                SELECT s.id, s.name, s.description, s.status, s.version, s.updated_at,
                       count(d.id) FILTER (WHERE d.status <> 'DELETED') AS document_count
                  FROM knowledge_space s
                  LEFT JOIN knowledge_document d
                    ON d.tenant_id = s.tenant_id AND d.space_id = s.id
                 WHERE s.tenant_id = ? AND s.status <> 'DELETED'
                 GROUP BY s.id, s.name, s.description, s.status, s.version, s.updated_at
                 ORDER BY s.updated_at DESC, s.id
                """, (row, number) -> new Space(
                row.getString("id"),
                row.getString("name"),
                row.getString("description"),
                row.getString("status"),
                row.getLong("version"),
                row.getLong("document_count"),
                instant(row, "updated_at")
        ), tenantId.value());
    }

    @Override
    public DocumentPage documents(TenantId tenantId, DocumentFilter filter) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(filter, "filter must not be null");

        int limit = Math.max(1, Math.min(filter.limit(), 200));
        int offset = Math.max(0, filter.offset());
        String spaceId = blankToNull(filter.spaceId());
        String status = blankToNull(filter.status());
        StringBuilder where = new StringBuilder("""
                 WHERE d.tenant_id = ?
                   AND d.status <> 'DELETED'
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId.value());
        if (spaceId != null) {
            where.append("   AND d.space_id = ?\n");
            parameters.add(spaceId);
        }
        if (status != null) {
            where.append("   AND d.status = ?\n");
            parameters.add(status);
        }

        Long totalValue = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_document d\n" + where,
                Long.class,
                parameters.toArray()
        );
        long total = totalValue == null ? 0L : totalValue;

        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(limit);
        pageParameters.add(offset);
        List<Document> items = jdbc.query("""
                SELECT d.id, d.space_id, d.title, d.source_type, d.source_uri, d.status,
                       d.authority, d.version, d.active_revision_id, d.updated_at,
                       count(c.id) AS chunk_count,
                       coalesce(p.keyword_status, 'SKIPPED') AS keyword_status,
                       coalesce(p.vector_status, 'SKIPPED') AS vector_status,
                       coalesce(p.graph_status, 'SKIPPED') AS graph_status
                  FROM knowledge_document d
                  LEFT JOIN knowledge_chunk c
                    ON c.tenant_id = d.tenant_id
                   AND c.document_id = d.id
                   AND c.revision_id = d.active_revision_id
                  LEFT JOIN LATERAL (
                        SELECT dip.keyword_status, dip.vector_status, dip.graph_status
                          FROM document_index_projection dip
                         WHERE dip.tenant_id = d.tenant_id
                           AND dip.document_id = d.id
                           AND dip.revision_id = d.active_revision_id
                         ORDER BY dip.updated_at DESC
                         LIMIT 1
                  ) p ON TRUE
                """ + where + """
                 GROUP BY d.id, d.space_id, d.title, d.source_type, d.source_uri,
                          d.status, d.authority, d.version, d.active_revision_id,
                          d.updated_at, p.keyword_status, p.vector_status, p.graph_status
                 ORDER BY d.updated_at DESC, d.id
                 LIMIT ? OFFSET ?
                """, (row, number) -> new Document(
                row.getObject("id", UUID.class),
                row.getString("space_id"),
                row.getString("title"),
                row.getString("source_type"),
                row.getString("source_uri"),
                row.getString("status"),
                row.getInt("authority"),
                row.getLong("version"),
                row.getObject("active_revision_id", UUID.class),
                row.getLong("chunk_count"),
                row.getString("keyword_status"),
                row.getString("vector_status"),
                row.getString("graph_status"),
                instant(row, "updated_at")
        ), pageParameters.toArray());
        return new DocumentPage(items, limit, offset, total);
    }

    @Override
    public List<Chunk> chunks(TenantId tenantId, UUID documentId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        return jdbc.query("""
                SELECT c.id, c.ordinal, c.section_path_json::text,
                       c.content, c.content_hash
                  FROM knowledge_chunk c
                  JOIN knowledge_document d
                    ON d.tenant_id = c.tenant_id
                   AND d.id = c.document_id
                   AND d.active_revision_id = c.revision_id
                 WHERE c.tenant_id = ? AND c.document_id = ?
                 ORDER BY c.ordinal
                """, (row, number) -> new Chunk(
                row.getObject("id", UUID.class),
                row.getInt("ordinal"),
                stringArray(row.getString("section_path_json")),
                row.getString("content"),
                row.getString("content_hash")
        ), tenantId.value(), documentId);
    }

    @Override
    public List<Connector> connectors(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return jdbc.query("""
                SELECT c.id, c.space_id, c.connector_type, c.display_name, c.status,
                       c.version, c.updated_at, r.id AS last_run_id,
                       r.status AS last_run_status,
                       r.started_at AS last_run_at
                  FROM connector_instance c
                  LEFT JOIN LATERAL (
                        SELECT id, status, started_at
                          FROM connector_sync_run
                         WHERE tenant_id = c.tenant_id AND connector_id = c.id
                         ORDER BY started_at DESC
                         LIMIT 1
                  ) r ON TRUE
                 WHERE c.tenant_id = ? AND c.status <> 'DELETED'
                 ORDER BY c.updated_at DESC, c.id
                """, (row, number) -> new Connector(
                row.getString("id"),
                row.getString("space_id"),
                row.getString("connector_type"),
                row.getString("display_name"),
                row.getString("status"),
                row.getLong("version"),
                row.getObject("last_run_id", UUID.class),
                row.getString("last_run_status"),
                instant(row, "last_run_at"),
                instant(row, "updated_at")
        ), tenantId.value());
    }

    @Override
    public List<Trace> traces(TenantId tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                SELECT id, request_id, principal_id, total_duration_ms,
                       result_count, created_at
                  FROM retrieval_trace
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """, (row, number) -> trace(row, List.of()),
                tenantId.value(), boundedLimit);
    }

    @Override
    public Optional<Trace> trace(TenantId tenantId, UUID traceId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Trace summary = jdbc.query("""
                SELECT id, request_id, principal_id, total_duration_ms,
                       result_count, created_at
                  FROM retrieval_trace
                 WHERE tenant_id = ? AND id = ?
                """, row -> row.next() ? trace(row, List.of()) : null,
                tenantId.value(), traceId);
        if (summary == null) {
            return Optional.empty();
        }
        List<TraceStep> steps = jdbc.query("""
                SELECT ordinal, step_name, duration_ms, input_count, output_count, status
                  FROM retrieval_trace_step
                 WHERE trace_id = ?
                 ORDER BY ordinal
                """, (row, number) -> new TraceStep(
                row.getInt("ordinal"),
                row.getString("step_name"),
                row.getLong("duration_ms"),
                row.getInt("input_count"),
                row.getInt("output_count"),
                row.getString("status")
        ), traceId);
        return Optional.of(new Trace(
                summary.id(),
                summary.requestId(),
                summary.principalId(),
                summary.totalDurationMs(),
                summary.resultCount(),
                summary.createdAt(),
                steps
        ));
    }

    private long count(String table, String predicate, String tenantId) {
        String sql = "SELECT count(*) FROM " + table
                + " WHERE tenant_id = ? AND " + predicate;
        Long value = jdbc.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0L : value;
    }

    private Trace trace(ResultSet row, List<TraceStep> steps) throws SQLException {
        return new Trace(
                row.getObject("id", UUID.class),
                row.getObject("request_id", UUID.class),
                row.getString("principal_id"),
                row.getLong("total_duration_ms"),
                row.getInt("result_count"),
                instant(row, "created_at"),
                steps
        );
    }

    private List<String> stringArray(String json) {
        try {
            JsonNode node = jsonMapper.readTree(json);
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(value.asString()));
            return List.copyOf(values);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cannot read persisted section path", failure);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
