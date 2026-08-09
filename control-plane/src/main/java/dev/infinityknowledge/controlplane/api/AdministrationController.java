package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.MarkdownIngestionService;
import dev.infinityknowledge.controlplane.application.ProjectionAdministrationService;
import dev.infinityknowledge.controlplane.application.TenantProvisioningService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 提供 API-first 的租户空间初始化和 Markdown 文档写入入口。
 */
@RestController
@RequestMapping("/api/v1")
public class AdministrationController {

    private final JwtPrincipalContextFactory principalFactory;
    private final TenantProvisioningService provisioningService;
    private final MarkdownIngestionService ingestionService;
    private final ProjectionAdministrationService projectionService;

    /**
     * 创建管理 API。
     *
     * @param principalFactory JWT 主体工厂
     * @param provisioningService 租户初始化服务
     * @param ingestionService Markdown 摄取服务
     */
    public AdministrationController(
            JwtPrincipalContextFactory principalFactory,
            TenantProvisioningService provisioningService,
            MarkdownIngestionService ingestionService,
            ProjectionAdministrationService projectionService
    ) {
        this.principalFactory = principalFactory;
        this.provisioningService = provisioningService;
        this.ingestionService = ingestionService;
        this.projectionService = projectionService;
    }

    /**
     * 幂等创建当前租户的知识空间。
     *
     * @param request 创建请求
     * @param jwt 已验证 JWT
     */
    @PostMapping("/spaces")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void createSpace(
            @Valid @RequestBody CreateSpaceRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        provisioningService.createSpace(principalFactory.create(jwt), request);
    }

    /**
     * 写入或更新一个 Markdown 文档。
     *
     * @param request 文档请求
     * @param jwt 已验证 JWT
     * @return 活动修订信息
     */
    @PostMapping("/documents/markdown")
    public MarkdownDocumentResponse ingestMarkdown(
            @Valid @RequestBody MarkdownDocumentRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ingestionService.ingest(principalFactory.create(jwt), request);
    }

    /**
     * Returns asynchronous projection status for one document.
     */
    @GetMapping("/documents/{documentId}/projections")
    public List<ProjectionJobResponse> projectionStatus(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return projectionService.find(principalFactory.create(jwt), documentId);
    }

    /**
     * Requeues a dead-lettered projection.
     */
    @PostMapping("/documents/{documentId}/projections/{projectionType}/retry")
    public ProjectionRetryResponse retryProjection(
            @PathVariable UUID documentId,
            @PathVariable String projectionType,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return new ProjectionRetryResponse(
                projectionService.retry(
                        principalFactory.create(jwt),
                        documentId,
                        projectionType
                )
        );
    }

    /**
     * Requeues all active document revisions in one space for configured external channels.
     */
    @PostMapping("/spaces/{spaceId}/projections/rebuild")
    public ProjectionRebuildResponse rebuildSpaceProjections(
            @PathVariable String spaceId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ProjectionRebuildResponse.from(
                projectionService.rebuild(
                        principalFactory.create(jwt),
                        spaceId
                )
        );
    }
}
