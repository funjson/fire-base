package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.CreateSpaceRequest;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

/**
 * 为经过身份提供方授权的租户管理员创建知识空间。
 */
@Service
public class TenantProvisioningService {

    private final KnowledgeGovernanceStore governanceStore;
    private final Clock clock;

    /**
     * 创建租户初始化服务。
     *
     * @param governanceStore 知识治理持久化端口
     * @param clock UTC 时钟
     */
    public TenantProvisioningService(
            KnowledgeGovernanceStore governanceStore,
            Clock clock
    ) {
        this.governanceStore = Objects.requireNonNull(
                governanceStore,
                "governanceStore must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 幂等创建租户、当前主体、空间、管理员 ACL 和 API 上传连接器。
     *
     * @param principal 已认证主体
     * @param request 创建请求
     */
    public void createSpace(PrincipalContext principal, CreateSpaceRequest request) {
        requireAdmin(principal);
        governanceStore.createSpace(
                principal,
                new KnowledgeSpaceId(request.spaceId()),
                request.name(),
                clock.instant()
        );
    }

    /**
     * 确保当前主体拥有租户管理员角色。
     */
    private static void requireAdmin(PrincipalContext principal) {
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
