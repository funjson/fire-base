package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.RetrievalSpaceCatalog;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingCandidate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 从 PostgreSQL 加载已授权且仍处于活动状态的空间路由摘要。
 *
 * <p>该适配器只读取名称和描述，不读取文档正文。查询中的空间集合来自授权结果，
 * 同时再次绑定租户和活动状态，避免陈旧授权快照跨越数据边界。</p>
 */
public final class PostgresRetrievalSpaceCatalog implements RetrievalSpaceCatalog {
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 创建空间路由目录适配器。
     *
     * @param jdbc 命名参数 JDBC 模板
     */
    public PostgresRetrievalSpaceCatalog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    /**
     * 按空间标识稳定排序返回摘要，模型失败时该顺序也可作为确定性降级顺序。
     */
    @Override
    public List<SpaceRoutingCandidate> findAllowed(
            TenantId tenantId,
            Set<KnowledgeSpaceId> allowedSpaceIds
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        allowedSpaceIds = Set.copyOf(Objects.requireNonNull(
                allowedSpaceIds,
                "allowedSpaceIds must not be null"
        ));
        if (allowedSpaceIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue(
                        "spaceIds",
                        allowedSpaceIds.stream().map(KnowledgeSpaceId::value).toList()
                );
        return jdbc.query("""
                SELECT id, name, description
                  FROM knowledge_space
                 WHERE tenant_id = :tenantId
                   AND status = 'ACTIVE'
                   AND id IN (:spaceIds)
                 ORDER BY id
                """, parameters, (row, rowNumber) -> new SpaceRoutingCandidate(
                new KnowledgeSpaceId(row.getString("id")),
                row.getString("name"),
                row.getString("description")
        ));
    }
}
