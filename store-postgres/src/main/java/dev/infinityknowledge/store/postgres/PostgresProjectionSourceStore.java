package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.spi.indexing.ProjectionJob;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionSourceStore;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 从 PostgreSQL 加载精确的不可变修订，供后台投影使用。
 */
public final class PostgresProjectionSourceStore implements ProjectionSourceStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    /**
     * 创建投影来源读取器。
     */
    public PostgresProjectionSourceStore(JdbcTemplate jdbc) {
        this(jdbc, JsonMapper.builder().build());
    }

    /**
     * 使用指定元数据编解码器创建投影来源读取器。
     */
    public PostgresProjectionSourceStore(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    @Override
    public ProjectionSource load(ProjectionJob job) {
        Objects.requireNonNull(job, "job must not be null");
        List<KnowledgeDocument> documents = jdbc.query("""
                SELECT d.id, d.tenant_id, d.space_id, d.title, d.connector_id,
                       d.source_type, d.external_id, d.source_uri, d.status,
                       d.authority, d.metadata_json::text AS metadata_json,
                       r.language, d.created_at, d.updated_at
                FROM knowledge_document d
                JOIN document_revision r
                  ON r.tenant_id = d.tenant_id
                 AND r.document_id = d.id
                WHERE d.tenant_id = ? AND d.id = ? AND r.id = ?
                """,
                (resultSet, rowNumber) -> new KnowledgeDocument(
                        new DocumentId(resultSet.getObject("id", UUID.class)),
                        job.tenantId(),
                        job.spaceId(),
                        resultSet.getString("title"),
                        new SourceDescriptor(
                                resultSet.getString("connector_id"),
                                SourceType.valueOf(resultSet.getString("source_type")),
                                resultSet.getString("external_id"),
                                resultSet.getString("source_uri"),
                                Map.of()
                        ),
                        DocumentStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("authority"),
                        metadata(
                                resultSet.getString("metadata_json"),
                                resultSet.getString("language")
                        ),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
                ),
                job.tenantId().value(),
                job.documentId().value(),
                job.revisionId()
        );
        if (documents.size() != 1) {
            throw new IllegalStateException("projection source document revision was not found");
        }
        List<KnowledgeChunk> chunks = jdbc.query("""
                SELECT id, document_id, revision_id, ordinal,
                       ARRAY(SELECT jsonb_array_elements_text(element_ids_json)) AS element_ids,
                       ARRAY(SELECT jsonb_array_elements_text(section_path_json)) AS section_path,
                       content, contextual_text, content_hash,
                       source_spans_json::text AS source_spans_json, metadata_json::text AS metadata_json
                FROM knowledge_chunk
                WHERE tenant_id = ? AND space_id = ?
                  AND document_id = ? AND revision_id = ?
                ORDER BY ordinal
                """,
                (resultSet, rowNumber) -> new KnowledgeChunk(
                        resultSet.getObject("id", UUID.class),
                        job.tenantId(),
                        job.spaceId(),
                        job.documentId(),
                        resultSet.getObject("revision_id", UUID.class),
                        uuidList(resultSet.getArray("element_ids")),
                        sourceSpans(resultSet.getString("source_spans_json")),
                        resultSet.getInt("ordinal"),
                        stringList(resultSet.getArray("section_path")),
                        resultSet.getString("content"),
                        resultSet.getString("contextual_text"),
                        resultSet.getString("content_hash"),
                        metadata(
                                resultSet.getString("metadata_json"),
                                null
                        )
                ),
                job.tenantId().value(),
                job.spaceId().value(),
                job.documentId().value(),
                job.revisionId()
        );
        return new ProjectionSource(documents.getFirst(), chunks);
    }

    private Map<String, String> metadata(String json, String language) {
        try {
            Map<String, String> values = json == null || json.isBlank()
                    ? new HashMap<>()
                    : new HashMap<>(jsonMapper.readValue(
                            json,
                            new TypeReference<Map<String, String>>() {
                            }
                    ));
            if (language != null && !language.isBlank()) {
                values.put("language", language);
            }
            return Map.copyOf(values);
        } catch (JacksonException parseFailure) {
            throw new IllegalStateException("projection metadata is invalid", parseFailure);
        }
    }

    private static List<String> stringList(Array sqlArray) throws SQLException {
        Object[] values = (Object[]) sqlArray.getArray();
        return Arrays.stream(values).map(Object::toString).toList();
    }

    private static List<UUID> uuidList(Array sqlArray) throws SQLException {
        return stringList(sqlArray).stream().map(UUID::fromString).toList();
    }

    private List<ChunkSourceSpan> sourceSpans(String json) {
        try {
            return json == null || json.isBlank()
                    ? List.of()
                    : jsonMapper.readValue(json, new TypeReference<List<ChunkSourceSpan>>() { });
        } catch (JacksonException parseFailure) {
            throw new IllegalStateException("stored chunk source spans are invalid", parseFailure);
        }
    }
}
