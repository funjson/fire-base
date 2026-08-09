package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 使用 PostgreSQL 实现知识空间、主体和 ACL 治理。
 */
public final class PostgresKnowledgeGovernanceStore
        implements KnowledgeGovernanceStore {

    private final NamedParameterJdbcTemplate jdbc;

    public PostgresKnowledgeGovernanceStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    @Transactional
    public void ensurePrincipal(PrincipalContext principal, Instant now) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(now, "now must not be null");
        var parameters = principalParameters(principal, now);
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (:tenantId, :tenantId, 'ACTIVE', :now, :now)
                ON CONFLICT (id) DO NOTHING
                """, parameters);
        jdbc.update("""
                INSERT INTO knowledge_principal
                    (tenant_id, principal_id, principal_type, display_name,
                     status, created_at, updated_at)
                VALUES (:tenantId, :principalId, :principalType, :principalId,
                        'ACTIVE', :now, :now)
                ON CONFLICT (tenant_id, principal_id) DO NOTHING
                """, parameters);
        String status = jdbc.queryForObject("""
                SELECT t.status || ':' || p.status
                  FROM knowledge_tenant t
                  JOIN knowledge_principal p
                    ON p.tenant_id = t.id
                   AND p.principal_id = :principalId
                 WHERE t.id = :tenantId
                """, parameters, String.class);
        if (!"ACTIVE:ACTIVE".equals(status)) {
            throw new KnowledgeAccessDeniedException(
                    "Knowledge tenant or principal is not active"
            );
        }
    }

    @Override
    @Transactional
    public void createSpace(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            String name,
            Instant now
    ) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        ensurePrincipal(principal, now);
        var parameters = principalParameters(principal, now)
                .addValue("spaceId", spaceId.value())
                .addValue("name", name.strip())
                .addValue("connectorId", "api-upload:" + spaceId.value());
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, status, created_at, updated_at)
                VALUES (:tenantId, :spaceId, :name, 'ACTIVE', :now, :now)
                ON CONFLICT (tenant_id, id) DO UPDATE
                SET name = EXCLUDED.name,
                    status = 'ACTIVE',
                    updated_at = EXCLUDED.updated_at
                """, parameters);
        jdbc.update("""
                INSERT INTO knowledge_space_acl
                    (tenant_id, space_id, subject_type, subject_id,
                     permission, granted_by, created_at)
                VALUES (:tenantId, :spaceId, 'USER', :principalId,
                        'ADMIN', :principalId, :now)
                ON CONFLICT DO NOTHING
                """, parameters);
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, created_at, updated_at)
                VALUES (:tenantId, :connectorId, :spaceId, 'API', 'API Upload',
                        '{}'::jsonb, 'ACTIVE', :now, :now)
                ON CONFLICT (tenant_id, id) DO UPDATE
                SET space_id = EXCLUDED.space_id,
                    status = 'ACTIVE',
                    updated_at = EXCLUDED.updated_at
                """, parameters);
    }

    @Override
    public List<AccessibleSpace> accessibleSpaces(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        var parameters = principalParameters(principal, Instant.EPOCH)
                .addValue("hasRoles", !principal.roleIds().isEmpty())
                .addValue("hasDepartments", !principal.departmentIds().isEmpty())
                .addValue("roleIds", nonEmpty(principal.roleIds()))
                .addValue("departmentIds", nonEmpty(principal.departmentIds()));
        String authorization = principal.systemPrincipal() ? """
                 WHERE s.tenant_id = :tenantId
                   AND s.status = 'ACTIVE'
                """ : """
                  JOIN knowledge_principal p
                    ON p.tenant_id = s.tenant_id
                   AND p.principal_id = :principalId
                   AND p.status = 'ACTIVE'
                 WHERE s.tenant_id = :tenantId
                   AND s.status = 'ACTIVE'
                   AND EXISTS (
                       SELECT 1
                         FROM knowledge_space_acl a
                        WHERE a.tenant_id = s.tenant_id
                          AND a.space_id = s.id
                          AND a.permission IN ('READ', 'WRITE', 'ADMIN')
                          AND (
                               (a.subject_type = 'USER'
                                    AND a.subject_id = :principalId)
                            OR (:hasRoles = TRUE
                                    AND a.subject_type = 'ROLE'
                                    AND a.subject_id IN (:roleIds))
                            OR (:hasDepartments = TRUE
                                    AND a.subject_type = 'DEPARTMENT'
                                    AND a.subject_id IN (:departmentIds))
                            OR (a.subject_type = 'TENANT'
                                    AND a.subject_id = :tenantId)
                          )
                   )
                """;
        return jdbc.query("""
                SELECT s.id, s.name, s.description, s.status, s.version, s.updated_at,
                       count(d.id) FILTER (WHERE d.status <> 'DELETED') AS document_count
                  FROM knowledge_space s
                  JOIN knowledge_tenant t
                    ON t.id = s.tenant_id
                   AND t.status = 'ACTIVE'
                  LEFT JOIN knowledge_document d
                    ON d.tenant_id = s.tenant_id
                   AND d.space_id = s.id
                """ + authorization + """
                 GROUP BY s.id, s.name, s.description, s.status, s.version, s.updated_at
                 ORDER BY s.name, s.id
                """, parameters, (row, number) -> accessibleSpace(row));
    }

    @Override
    public List<SpaceGrant> grants(TenantId tenantId, KnowledgeSpaceId spaceId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        return jdbc.query("""
                SELECT subject_type, subject_id, permission
                  FROM knowledge_space_acl
                 WHERE tenant_id = :tenantId
                   AND space_id = :spaceId
                 ORDER BY subject_type, subject_id, permission
                """, spaceParameters(tenantId, spaceId), (row, number) -> new SpaceGrant(
                SubjectType.valueOf(row.getString("subject_type")),
                row.getString("subject_id"),
                Permission.valueOf(row.getString("permission"))
        ));
    }

    @Override
    public void grant(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SpaceGrant grant,
            PrincipalId grantedBy,
            Instant now
    ) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(grantedBy, "grantedBy must not be null");
        Objects.requireNonNull(now, "now must not be null");
        var parameters = spaceParameters(tenantId, spaceId)
                .addValue("subjectType", grant.subjectType().name())
                .addValue("subjectId", grant.subjectId())
                .addValue("permission", grant.permission().name())
                .addValue("grantedBy", grantedBy.value())
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO knowledge_space_acl
                    (tenant_id, space_id, subject_type, subject_id,
                     permission, granted_by, created_at)
                VALUES (:tenantId, :spaceId, :subjectType, :subjectId,
                        :permission, :grantedBy, :now)
                ON CONFLICT DO NOTHING
                """, parameters);
    }

    @Override
    public boolean revoke(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SpaceGrant grant
    ) {
        Objects.requireNonNull(grant, "grant must not be null");
        var parameters = spaceParameters(tenantId, spaceId)
                .addValue("subjectType", grant.subjectType().name())
                .addValue("subjectId", grant.subjectId())
                .addValue("permission", grant.permission().name());
        return jdbc.update("""
                DELETE FROM knowledge_space_acl
                 WHERE tenant_id = :tenantId
                   AND space_id = :spaceId
                   AND subject_type = :subjectType
                   AND subject_id = :subjectId
                   AND permission = :permission
                """, parameters) > 0;
    }

    private MapSqlParameterSource principalParameters(
            PrincipalContext principal,
            Instant now
    ) {
        return new MapSqlParameterSource()
                .addValue("tenantId", principal.tenantId().value())
                .addValue("principalId", principal.principalId().value())
                .addValue(
                        "principalType",
                        principal.systemPrincipal() ? "SERVICE" : "USER"
                )
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    private MapSqlParameterSource spaceParameters(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("spaceId", spaceId.value());
    }

    private AccessibleSpace accessibleSpace(ResultSet row) throws SQLException {
        return new AccessibleSpace(
                row.getString("id"),
                row.getString("name"),
                row.getString("description"),
                row.getString("status"),
                row.getLong("version"),
                row.getLong("document_count"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private Set<String> nonEmpty(Set<String> values) {
        return values.isEmpty() ? Set.of("__none__") : values;
    }
}
