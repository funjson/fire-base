package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.KnowledgeGovernanceService;
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

    public SpaceGovernanceController(
            JwtPrincipalContextFactory principalFactory,
            KnowledgeGovernanceService governance
    ) {
        this.principalFactory = principalFactory;
        this.governance = governance;
    }

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
                        space.updatedAt()
                ))
                .toList();
    }

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
