package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.Retriever;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Routes queries through published Wiki pages and returns their original active source chunks.
 * Generated page prose never masquerades as primary evidence: every result retains a real
 * document, revision and chunk citation accepted by the normal active-revision guard.
 */
public final class PostgresPublishedPageRetriever implements Retriever {
    private static final String SQL = """
            WITH page_matches AS (
                SELECT p.tenant_id, p.id AS page_id, p.space_id, p.slug,
                       p.title AS page_title, r.id AS page_revision_id,
                       r.sources_json,
                       GREATEST(
                           ts_rank_cd(
                               to_tsvector('simple', concat_ws(' ', p.title, r.summary, r.markdown)),
                               websearch_to_tsquery('simple', :query)
                           ),
                           CASE WHEN strpos(lower(p.title), lower(:query)) > 0
                               THEN 0.95 ELSE 0 END,
                           CASE WHEN strpos(lower(r.summary), lower(:query)) > 0
                               THEN 0.80 ELSE 0 END
                       ) AS page_score
                  FROM knowledge_page p
                  JOIN knowledge_page_revision r
                    ON r.tenant_id = p.tenant_id
                   AND r.id = p.active_revision_id
                 WHERE p.tenant_id = :tenantId
                   AND p.space_id IN (:spaceIds)
                   AND p.active_revision_id IS NOT NULL
                   AND p.status <> 'ARCHIVED'
                   AND (
                       to_tsvector('simple', concat_ws(' ', p.title, r.summary, r.markdown))
                           @@ websearch_to_tsquery('simple', :query)
                       OR strpos(lower(p.title), lower(:query)) > 0
                       OR strpos(lower(r.summary), lower(:query)) > 0
                   )
            ), routed AS (
                SELECT c.id AS chunk_id, c.tenant_id, c.space_id,
                       c.document_id, c.revision_id, d.title, d.source_uri,
                       d.authority, c.content,
                       COALESCE((
                           SELECT string_agg(value, chr(31))
                             FROM jsonb_array_elements_text(c.section_path_json)
                       ), '') AS section_path,
                       pm.page_id, pm.page_revision_id, pm.slug, pm.page_title,
                       LEAST(pm.page_score, 0.999999) AS score
                  FROM page_matches pm
                  CROSS JOIN LATERAL jsonb_array_elements(pm.sources_json) AS sources(source)
                  JOIN knowledge_chunk c
                    ON c.tenant_id = pm.tenant_id
                   AND c.space_id = pm.space_id
                   AND c.document_id = (source ->> 'documentId')::uuid
                   AND c.revision_id = (source ->> 'revisionId')::uuid
                   AND c.id = (source ->> 'chunkId')::uuid
                  JOIN knowledge_document d
                    ON d.tenant_id = c.tenant_id
                   AND d.id = c.document_id
                   AND d.active_revision_id = c.revision_id
                  JOIN document_revision dr
                    ON dr.tenant_id = c.tenant_id
                   AND dr.id = c.revision_id
                 WHERE d.status = 'ACTIVE'
                   AND (CAST(:sourceType AS varchar) IS NULL
                        OR d.source_type = CAST(:sourceType AS varchar))
                   AND (CAST(:language AS varchar) IS NULL
                        OR dr.language = CAST(:language AS varchar))
            %s
            ), deduplicated AS (
                SELECT DISTINCT ON (chunk_id) *
                  FROM routed
                 ORDER BY chunk_id, score DESC, page_id
            )
            SELECT chunk_id, tenant_id, space_id, document_id, revision_id,
                   title, source_uri, authority, content, section_path,
                   page_id, page_revision_id, slug, page_title, score
              FROM deduplicated
             WHERE score > 0
             ORDER BY score DESC, chunk_id
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresPublishedPageRetriever(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public RetrievalChannel channel() {
        return RetrievalChannel.PAGE;
    }

    @Override
    public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.accessScope().deniesAll()) {
            return List.of();
        }
        String documentClause = request.accessScope().restrictsDocuments()
                ? " AND c.document_id IN (:documentIds)\n"
                : "";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("query", request.plan().normalizedQuery())
                .addValue("tenantId", request.accessScope().tenantId().value())
                .addValue("spaceIds", request.accessScope().spaceIds().stream()
                        .map(KnowledgeSpaceId::value)
                        .toList())
                .addValue("sourceType", filter(request, "sourceType"))
                .addValue("language", filter(request, "language"))
                .addValue("limit", request.plan().candidateLimit());
        if (request.accessScope().restrictsDocuments()) {
            parameters.addValue("documentIds", request.accessScope().documentIds().stream()
                    .map(UUID::fromString)
                    .toList());
        }
        return List.copyOf(jdbc.query(
                SQL.formatted(documentClause),
                parameters,
                (row, rowNumber) -> new RetrievalCandidate(
                        row.getObject("chunk_id", UUID.class),
                        new TenantId(row.getString("tenant_id")),
                        new KnowledgeSpaceId(row.getString("space_id")),
                        new DocumentId(row.getObject("document_id", UUID.class)),
                        row.getObject("revision_id", UUID.class),
                        RetrievalChannel.PAGE,
                        rowNumber + 1,
                        row.getDouble("score"),
                        row.getString("title"),
                        sectionPath(row.getString("section_path")),
                        row.getString("content"),
                        row.getString("source_uri"),
                        Map.of(
                                "retriever", "published-page",
                                "authority", Integer.toString(row.getInt("authority")),
                                "pageId", row.getObject("page_id", UUID.class).toString(),
                                "pageRevisionId", row.getObject(
                                        "page_revision_id",
                                        UUID.class
                                ).toString(),
                                "pageSlug", row.getString("slug"),
                                "pageTitle", row.getString("page_title")
                        )
                )
        ));
    }

    private static String filter(RetrievalRequest request, String name) {
        String value = request.query().filters().get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> sectionPath(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split("\u001F", -1)).toList();
    }
}
