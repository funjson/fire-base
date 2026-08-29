package dev.infinityknowledge.controlplane.api.governance;

import dev.infinityknowledge.controlplane.application.governance.KnowledgeManagementService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 面向管理控制台的租户级只读 API。
 */
@RestController
@RequestMapping("/api/v1/admin")
public final class ManagementController {
    private final KnowledgeManagementService management;
    private final JwtPrincipalContextFactory principals;

    public ManagementController(
            KnowledgeManagementService management,
            JwtPrincipalContextFactory principals
    ) {
        this.management = management;
        this.principals = principals;
    }

    @GetMapping("/overview")
    public ManagementViews.Overview overview(@AuthenticationPrincipal Jwt jwt) {
        return management.overview(principals.create(jwt));
    }

    @GetMapping("/spaces")
    public List<ManagementViews.Space> spaces(@AuthenticationPrincipal Jwt jwt) {
        return management.spaces(principals.create(jwt));
    }

    @GetMapping("/documents")
    public ManagementViews.Page<ManagementViews.Document> documents(
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keywordStatus,
            @RequestParam(required = false) String vectorStatus,
            @RequestParam(required = false) Integer minimumChunkCount,
            @RequestParam(required = false) Integer maximumChunkCount,
            @RequestParam(required = false) Instant updatedFrom,
            @RequestParam(required = false) Instant updatedTo,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return management.documents(
                principals.create(jwt),
                spaceId,
                status,
                title,
                source,
                keywordStatus,
                vectorStatus,
                minimumChunkCount,
                maximumChunkCount,
                updatedFrom,
                updatedTo,
                limit,
                offset
        );
    }

    /** 返回可直接刷新和收藏的文档二级详情页摘要。 */
    @GetMapping("/documents/{documentId}")
    public ManagementViews.Document document(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return management.document(principals.create(jwt), documentId);
    }

    @GetMapping("/documents/{documentId}/chunks")
    public List<ManagementViews.Chunk> chunks(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return management.chunks(principals.create(jwt), documentId);
    }

    @GetMapping("/documents/{documentId}/revisions")
    public List<ManagementViews.Revision> revisions(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return management.revisions(principals.create(jwt), documentId);
    }

    @GetMapping("/documents/{documentId}/revisions/{revisionId}/chunks")
    public List<ManagementViews.Chunk> revisionChunks(
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return management.chunks(principals.create(jwt), documentId, revisionId);
    }

    @GetMapping("/connectors")
    public List<ManagementViews.Connector> connectors(@AuthenticationPrincipal Jwt jwt) {
        return management.connectors(principals.create(jwt));
    }

    @GetMapping("/traces")
    public List<ManagementViews.Trace> traces(
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return management.traces(principals.create(jwt), limit);
    }

    @GetMapping("/traces/{traceId}")
    public ManagementViews.Trace trace(
            @PathVariable UUID traceId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return management.trace(principals.create(jwt), traceId);
    }
}
