package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.KnowledgeManagementService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant administration read API consumed by the visual console.
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
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return management.documents(
                principals.create(jwt), spaceId, status, limit, offset
        );
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
