package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 编排已认证主体登记、可访问空间和空间 ACL 管理。
 */
@Service
public final class KnowledgeGovernanceService {

    private final KnowledgeGovernanceStore store;
    private final Clock clock;

    public KnowledgeGovernanceService(KnowledgeGovernanceStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 幂等登记身份提供方已经认证的主体。
     */
    public void ensurePrincipal(PrincipalContext principal) {
        store.ensurePrincipal(
                Objects.requireNonNull(principal, "principal must not be null"),
                clock.instant()
        );
    }

    /**
     * 返回当前主体可读取的活动空间。
     */
    public List<KnowledgeGovernanceStore.AccessibleSpace> accessibleSpaces(
            PrincipalContext principal
    ) {
        ensurePrincipal(principal);
        return store.accessibleSpaces(principal);
    }

    /**
     * 返回指定空间 ACL。该管理能力只对知识管理员开放。
     */
    public List<KnowledgeGovernanceStore.SpaceGrant> grants(
            PrincipalContext principal,
            String spaceId
    ) {
        requireAdmin(principal);
        return store.grants(
                principal.tenantId(),
                new KnowledgeSpaceId(spaceId)
        );
    }

    /**
     * 幂等授予一个空间权限。
     */
    public void grant(
            PrincipalContext principal,
            String spaceId,
            KnowledgeGovernanceStore.SpaceGrant grant
    ) {
        requireAdmin(principal);
        store.grant(
                principal.tenantId(),
                new KnowledgeSpaceId(spaceId),
                grant,
                principal.principalId(),
                clock.instant()
        );
    }

    /**
     * 撤销一个精确匹配的空间权限。
     */
    public boolean revoke(
            PrincipalContext principal,
            String spaceId,
            KnowledgeGovernanceStore.SpaceGrant grant
    ) {
        requireAdmin(principal);
        return store.revoke(
                principal.tenantId(),
                new KnowledgeSpaceId(spaceId),
                grant
        );
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal()
                && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
