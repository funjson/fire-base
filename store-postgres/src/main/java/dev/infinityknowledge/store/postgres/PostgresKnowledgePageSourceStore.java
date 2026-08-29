package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.wiki.KnowledgePageSourceStore;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL loader for explicitly selected immutable page sources. */
public final class PostgresKnowledgePageSourceStore implements KnowledgePageSourceStore {
    private static final int MAX_SOURCES = 100;

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public PostgresKnowledgePageSourceStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    public List<LoadedSource> load(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            List<SourceSelection> selections
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        selections = List.copyOf(Objects.requireNonNull(
                selections,
                "selections must not be null"
        ));
        if (selections.isEmpty() || selections.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("page sources must contain between 1 and 100 items");
        }
        if (selections.stream().distinct().count() != selections.size()) {
            throw new IllegalArgumentException("page sources must not contain duplicates");
        }
        return selections.stream()
                .map(selection -> loadOne(tenantId, spaceId, selection))
                .toList();
    }

    private LoadedSource loadOne(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SourceSelection selection
    ) {
        List<LoadedSource> matches = jdbc.query("""
                SELECT c.id, c.document_id, c.revision_id, c.ordinal,
                       c.element_ids_json::text AS element_ids_json,
                       c.section_path_json::text AS section_path_json,
                       c.content, c.contextual_text, c.content_hash,
                       c.source_spans_json::text AS source_spans_json,
                       c.metadata_json::text AS metadata_json,
                       d.authority
                  FROM knowledge_chunk c
                  JOIN knowledge_document d
                    ON d.tenant_id = c.tenant_id
                   AND d.space_id = c.space_id
                   AND d.id = c.document_id
                 WHERE c.tenant_id = ? AND c.space_id = ?
                   AND c.document_id = ? AND c.revision_id = ? AND c.id = ?
                   AND d.status = 'ACTIVE'
                   AND d.active_revision_id = c.revision_id
                """, (row, ignored) -> new LoadedSource(
                new KnowledgeChunk(
                        row.getObject("id", UUID.class),
                        tenantId,
                        spaceId,
                        selection.documentId(),
                        row.getObject("revision_id", UUID.class),
                        uuidList(row.getString("element_ids_json")),
                        sourceSpans(row.getString("source_spans_json")),
                        row.getInt("ordinal"),
                        stringList(row.getString("section_path_json")),
                        row.getString("content"),
                        row.getString("contextual_text"),
                        row.getString("content_hash"),
                        stringMap(row.getString("metadata_json"))
                ),
                row.getInt("authority")
        ), tenantId.value(), spaceId.value(), selection.documentId().value(),
                selection.revisionId(), selection.chunkId());
        if (matches.size() != 1) {
            throw new IllegalArgumentException("selected page source does not exist");
        }
        return matches.getFirst();
    }

    private List<UUID> uuidList(String json) {
        return stringList(json).stream().map(UUID::fromString).toList();
    }

    private List<String> stringList(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (RuntimeException failure) {
            throw new IllegalStateException("stored page source list is invalid", failure);
        }
    }

    private Map<String, String> stringMap(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (RuntimeException failure) {
            throw new IllegalStateException("stored page source metadata is invalid", failure);
        }
    }

    private List<ChunkSourceSpan> sourceSpans(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<List<ChunkSourceSpan>>() { });
        } catch (RuntimeException failure) {
            throw new IllegalStateException("stored page source spans are invalid", failure);
        }
    }
}
