package dev.infinityknowledge.controlplane.api.projection;

import dev.infinityknowledge.controlplane.application.ingestion.MarkdownIngestionService;
import dev.infinityknowledge.controlplane.application.projection.ProjectionAdministrationService;
import dev.infinityknowledge.controlplane.api.document.MarkdownDocumentRequest;
import dev.infinityknowledge.controlplane.api.document.MarkdownDocumentResponse;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 提供 Markdown 文档写入和投影管理入口。
 */
@RestController
@RequestMapping("/api/v1")
public class AdministrationController {

    private final JwtPrincipalContextFactory principalFactory;
    private final MarkdownIngestionService ingestionService;
    private final ProjectionAdministrationService projectionService;

    /**
     * 创建管理 API。
     *
     * @param principalFactory JWT 主体工厂
     * @param ingestionService Markdown 摄取服务
     * @param projectionService 投影管理服务
     */
    public AdministrationController(
            JwtPrincipalContextFactory principalFactory,
            MarkdownIngestionService ingestionService,
            ProjectionAdministrationService projectionService
    ) {
        this.principalFactory = principalFactory;
        this.ingestionService = ingestionService;
        this.projectionService = projectionService;
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

    /** 返回一个文档当前的异步投影状态。 */
    @GetMapping("/documents/{documentId}/projections")
    public List<ProjectionJobResponse> projectionStatus(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return projectionService.find(principalFactory.create(jwt), documentId);
    }

    /** 将一个已进入死信状态的投影重新排队。 */
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

    /** 为一个空间的全部活动修订重排当前启用的外部投影。 */
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
