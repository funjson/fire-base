package dev.infinityknowledge.spi.governance;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 知识空间、主体和 ACL 的事务端口。
 *
 * <p>该端口按治理用例聚合持久化操作，避免 Control Plane 直接执行 SQL，
 * 同时不为每张治理表创建单独 Repository。</p>
 */
public interface KnowledgeGovernanceStore {

    /**
     * 幂等登记已经通过身份提供方认证的主体。
     */
    void ensurePrincipal(PrincipalContext principal, Instant now);

    /**
     * 幂等创建知识空间，并为创建者授予管理员权限。
     */
    void createSpace(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            String name,
            Instant now
    );

    /**
     * 返回当前主体可以读取的活动知识空间。
     */
    List<AccessibleSpace> accessibleSpaces(PrincipalContext principal);

    /**
     * 返回指定空间当前的 ACL。
     */
    List<SpaceGrant> grants(TenantId tenantId, KnowledgeSpaceId spaceId);

    /**
     * 幂等授予一个空间权限。
     */
    void grant(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SpaceGrant grant,
            PrincipalId grantedBy,
            Instant now
    );

    /**
     * 撤销一个精确匹配的空间权限。
     *
     * @return 是否实际删除了一条授权
     */
    boolean revoke(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SpaceGrant grant
    );

    enum SubjectType {
        USER,
        ROLE,
        DEPARTMENT,
        TENANT
    }

    enum Permission {
        READ,
        WRITE,
        ADMIN
    }

    record SpaceGrant(
            SubjectType subjectType,
            String subjectId,
            Permission permission
    ) {
        public SpaceGrant {
            Objects.requireNonNull(subjectType, "subjectType must not be null");
            subjectId = requireText(subjectId, "subjectId");
            Objects.requireNonNull(permission, "permission must not be null");
        }
    }

    record AccessibleSpace(
            String id,
            String name,
            String description,
            String status,
            long version,
            long documentCount,
            Instant updatedAt
    ) {
        public AccessibleSpace {
            id = requireText(id, "id");
            name = requireText(name, "name");
            description = Objects.requireNonNull(description, "description must not be null");
            status = requireText(status, "status");
            Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
