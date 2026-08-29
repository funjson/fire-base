package dev.infinityknowledge.controlplane.api.governance;

import dev.infinityknowledge.controlplane.api.document.DocumentProcessingConfigRequestMapper;
import dev.infinityknowledge.controlplane.application.governance.KnowledgeGovernanceService;
import dev.infinityknowledge.controlplane.application.governance.TenantProvisioningService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 提供当前主体可访问空间和管理员 ACL 操作。
 */
@RestController
@RequestMapping("/api/v1/spaces")
public final class SpaceGovernanceController {

    private final JwtPrincipalContextFactory principalFactory;
    private final KnowledgeGovernanceService governance;
    private final TenantProvisioningService provisioningService;

    /** 创建只负责 Space 生命周期和 ACL HTTP 适配的治理控制器。 */
    public SpaceGovernanceController(
            JwtPrincipalContextFactory principalFactory,
            KnowledgeGovernanceService governance,
            TenantProvisioningService provisioningService
    ) {
        this.principalFactory = principalFactory;
        this.governance = governance;
        this.provisioningService = provisioningService;
    }

    /**
     * 幂等创建当前租户的知识空间，并同时固化不可变文档处理配置。
     *
     * @param request 创建请求
     * @param jwt 已验证 JWT
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void createSpace(
            @Valid @RequestBody CreateSpaceRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var principal = principalFactory.create(jwt);
        provisioningService.createSpace(
                principal,
                request.spaceId(),
                request.name(),
                request.description(),
                DocumentProcessingConfigRequestMapper.map(
                        principal,
                        request.spaceId(),
                        request.documentProcessingConfig()
                )
        );
    }

    /** 返回当前主体依据租户和 ACL 可以读取的活动 Space。 */
    @GetMapping("/accessible")
    public List<SpaceGovernanceViews.AccessibleSpace> accessibleSpaces(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return governance.accessibleSpaces(principalFactory.create(jwt)).stream()
                .map(space -> new SpaceGovernanceViews.AccessibleSpace(
                        space.id(),
                        space.name(),
                        space.description(),
                        space.status(),
                        space.version(),
                        space.documentCount(),
                        space.createdAt(),
                        space.updatedAt()
                ))
                .toList();
    }

    /** 返回目标 Space 的精确授权清单。 */
    @GetMapping("/{spaceId}/acl")
    public List<SpaceGovernanceViews.Grant> grants(
            @PathVariable String spaceId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return governance.grants(principalFactory.create(jwt), spaceId).stream()
                .map(grant -> new SpaceGovernanceViews.Grant(
                        grant.subjectType().name(),
                        grant.subjectId(),
                        grant.permission().name()
                ))
                .toList();
    }

    /** 幂等增加一条 Space 授权。 */
    @PostMapping("/{spaceId}/acl")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(
            @PathVariable String spaceId,
            @Valid @RequestBody SpaceAclRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        governance.grant(
                principalFactory.create(jwt),
                spaceId,
                grant(request)
        );
    }

    /** 幂等撤销一条精确匹配的 Space 授权。 */
    @DeleteMapping("/{spaceId}/acl")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable String spaceId,
            @Valid @RequestBody SpaceAclRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        governance.revoke(
                principalFactory.create(jwt),
                spaceId,
                grant(request)
        );
    }

    private KnowledgeGovernanceStore.SpaceGrant grant(SpaceAclRequest request) {
        try {
            return new KnowledgeGovernanceStore.SpaceGrant(
                    KnowledgeGovernanceStore.SubjectType.valueOf(
                            request.subjectType().strip().toUpperCase(Locale.ROOT)
                    ),
                    request.subjectId(),
                    KnowledgeGovernanceStore.Permission.valueOf(
                            request.permission().strip().toUpperCase(Locale.ROOT)
                    )
            );
        } catch (IllegalArgumentException invalidValue) {
            throw new IllegalArgumentException(
                    "Unsupported ACL subject type or permission",
                    invalidValue
            );
        }
    }
}
