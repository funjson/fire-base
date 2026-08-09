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
 * 使用 PostgreSQL 全文索引和精确子串匹配实现安全的关键词召回。
 *
 * <p>该实现适合作为零额外中间件的可运行基线。生产环境可注册 Elasticsearch
 * Retriever 替换此通道，Runtime 和 Agent API 无需改变。</p>
 */
public final class PostgresKeywordRetriever implements Retriever {

    private static final String BASE_SQL = """
            WITH scored AS (
                SELECT c.id AS chunk_id,
                       c.tenant_id,
                       c.space_id,
                       c.document_id,
                       c.revision_id,
                       d.title,
                       d.source_uri,
                       d.authority,
                       c.content,
                       COALESCE((
                           SELECT string_agg(value, chr(31))
                           FROM jsonb_array_elements_text(c.section_path_json)
                       ), '') AS section_path,
                       GREATEST(
                           ts_rank_cd(c.search_vector, websearch_to_tsquery('simple', :query)),
                           CASE WHEN strpos(lower(c.content), lower(:query)) > 0
                               THEN 0.75 ELSE 0 END,
                           CASE WHEN strpos(lower(d.title), lower(:query)) > 0
                               THEN 0.95 ELSE 0 END
                       ) AS raw_score
                FROM knowledge_chunk c
                JOIN knowledge_document d
                  ON d.tenant_id = c.tenant_id
                 AND d.id = c.document_id
                 AND d.active_revision_id = c.revision_id
                JOIN document_revision r
                  ON r.tenant_id = c.tenant_id
                 AND r.id = c.revision_id
                WHERE c.tenant_id = :tenantId
                  AND c.space_id IN (:spaceIds)
                  AND d.status = 'ACTIVE'
                  AND (CAST(:sourceType AS varchar) IS NULL
                       OR d.source_type = CAST(:sourceType AS varchar))
                  AND (CAST(:language AS varchar) IS NULL
                       OR r.language = CAST(:language AS varchar))
                  AND (
                      c.search_vector @@ websearch_to_tsquery('simple', :query)
                      OR strpos(lower(c.content), lower(:query)) > 0
                      OR strpos(lower(d.title), lower(:query)) > 0
                  )
            %s
            )
            SELECT chunk_id, tenant_id, space_id, document_id, revision_id,
                   title, source_uri, authority, content, section_path,
                   LEAST(raw_score, 0.999999) AS score
            FROM scored
            WHERE raw_score > 0
            ORDER BY raw_score DESC, chunk_id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 创建 PostgreSQL 关键词 Retriever。
     *
     * @param jdbc 命名参数 JDBC 模板
     */
    public PostgresKeywordRetriever(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    /**
     * 返回关键词通道。
     *
     * @return 关键词通道
     */
    @Override
    public RetrievalChannel channel() {
        return RetrievalChannel.KEYWORD;
    }

    /**
     * 在预先计算的租户、空间和可选文档白名单内召回候选。
     *
     * @param request 检索请求
     * @return 相关性有序的候选
     */
    @Override
    public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.accessScope().deniesAll()) {
            return List.of();
        }
        Map<String, String> filters = request.query().filters();
        String documentClause = request.accessScope().restrictsDocuments()
                ? " AND c.document_id IN (:documentIds)\n"
                : "";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("query", request.plan().normalizedQuery())
                .addValue("tenantId", request.accessScope().tenantId().value())
                .addValue(
                        "spaceIds",
                        request.accessScope().spaceIds().stream()
                                .map(KnowledgeSpaceId::value)
                                .toList()
                )
                .addValue("sourceType", nullableFilter(filters, "sourceType"))
                .addValue("language", nullableFilter(filters, "language"))
                .addValue("limit", request.plan().candidateLimit());
        if (request.accessScope().restrictsDocuments()) {
            parameters.addValue(
                    "documentIds",
                    request.accessScope().documentIds().stream().map(UUID::fromString).toList()
            );
        }
        List<RetrievalCandidate> candidates = jdbc.query(
                BASE_SQL.formatted(documentClause),
                parameters,
                (resultSet, rowNumber) -> new RetrievalCandidate(
                        resultSet.getObject("chunk_id", UUID.class),
                        new TenantId(resultSet.getString("tenant_id")),
                        new KnowledgeSpaceId(resultSet.getString("space_id")),
                        new DocumentId(resultSet.getObject("document_id", UUID.class)),
                        resultSet.getObject("revision_id", UUID.class),
                        RetrievalChannel.KEYWORD,
                        rowNumber + 1,
                        resultSet.getDouble("score"),
                        resultSet.getString("title"),
                        sectionPath(resultSet.getString("section_path")),
                        resultSet.getString("content"),
                        resultSet.getString("source_uri"),
                        Map.of(
                                "retriever", "postgresql-fts",
                                "authority", Integer.toString(resultSet.getInt("authority"))
                        )
                )
        );
        return List.copyOf(candidates);
    }

    /**
     * 读取可选过滤条件并把空白值视为未提供。
     */
    private static String nullableFilter(Map<String, String> filters, String name) {
        String value = filters.get(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 将数据库中的不可见分隔符恢复为章节路径。
     */
    private static List<String> sectionPath(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split("\u001F", -1)).toList();
    }
}
