package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 使用 PostgreSQL 知识空间 ACL 解析主体可访问范围。
 *
 * <p>角色和部门来自已验证身份上下文，数据库仍要求主体在当前租户中为 ACTIVE。
 * 查询只返回空间 ID，不读取任何知识正文。</p>
 */
public final class PostgresAccessPolicy implements AccessPolicy {
    private static final String SYSTEM_ACCESSIBLE_SPACES_SQL = """
            SELECT s.id
              FROM knowledge_space s
              JOIN knowledge_tenant t
                ON t.id = s.tenant_id
               AND t.status = 'ACTIVE'
             WHERE s.tenant_id = :tenantId
               AND s.status = 'ACTIVE'
            """;
    private static final String ACCESSIBLE_SPACES_SQL = """
            SELECT DISTINCT s.id
              FROM knowledge_space s
              JOIN knowledge_tenant t
                ON t.id = s.tenant_id
               AND t.status = 'ACTIVE'
              JOIN knowledge_principal p
                ON p.tenant_id = s.tenant_id
               AND p.principal_id = :principalId
               AND p.status = 'ACTIVE'
              JOIN knowledge_space_acl a
                ON a.tenant_id = s.tenant_id
               AND a.space_id = s.id
             WHERE s.tenant_id = :tenantId
               AND s.status = 'ACTIVE'
               AND a.permission IN ('READ', 'WRITE', 'ADMIN')
               AND (
                    (a.subject_type = 'USER' AND a.subject_id = :principalId)
                 OR (:hasRoles = TRUE
                        AND a.subject_type = 'ROLE'
                        AND a.subject_id IN (:roleIds))
                 OR (:hasDepartments = TRUE
                        AND a.subject_type = 'DEPARTMENT'
                        AND a.subject_id IN (:departmentIds))
                 OR (a.subject_type = 'TENANT' AND a.subject_id = :tenantId)
               )
            """;
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 创建 PostgreSQL 授权策略。
     *
     * @param jdbc 命名参数 JDBC 模板
     */
    public PostgresAccessPolicy(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    /**
     * 查询主体可访问空间并与请求空间取交集。
     *
     * @param principal 已认证主体
     * @param requestedSpaces 请求空间
     * @return 非空授权范围
     */
    @Override
    public AccessScope resolve(
            PrincipalContext principal,
            Set<KnowledgeSpaceId> requestedSpaces
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(requestedSpaces, "requestedSpaces must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", principal.tenantId().value())
                .addValue("principalId", principal.principalId().value())
                .addValue("hasRoles", !principal.roleIds().isEmpty())
                .addValue("hasDepartments", !principal.departmentIds().isEmpty())
                .addValue("roleIds", nonEmpty(principal.roleIds()))
                .addValue("departmentIds", nonEmpty(principal.departmentIds()));
        String query = principal.systemPrincipal()
                ? SYSTEM_ACCESSIBLE_SPACES_SQL
                : ACCESSIBLE_SPACES_SQL;
        List<KnowledgeSpaceId> rows = jdbc.query(
                query,
                parameters,
                (resultSet, rowNumber) -> new KnowledgeSpaceId(resultSet.getString("id"))
        );
        Set<KnowledgeSpaceId> accessible = new HashSet<>(rows);
        if (!requestedSpaces.isEmpty()) {
            accessible.retainAll(requestedSpaces);
        }
        if (accessible.isEmpty()) {
            throw new KnowledgeAccessDeniedException("No readable knowledge space");
        }
        return AccessScope.all(principal.tenantId(), accessible);
    }

    /**
     * 为 SQL IN 子句提供不会匹配真实主体的占位值，避免空集合生成非法 SQL。
     *
     * @param values 原始角色或部门集合
     * @return 非空集合
     */
    private Set<String> nonEmpty(Set<String> values) {
        return values.isEmpty() ? Set.of("__none__") : values;
    }
}
