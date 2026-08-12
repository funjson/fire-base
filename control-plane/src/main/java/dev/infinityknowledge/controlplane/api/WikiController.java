package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.WikiApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import dev.infinityknowledge.domain.wiki.KnowledgePageStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Tenant administration API for compiling and publishing Wiki pages. */
@RestController
@RequestMapping("/api/v1/admin/wiki/pages")
public final class WikiController {
    private final WikiApplicationService wiki;
    private final JwtPrincipalContextFactory principals;

    public WikiController(
            WikiApplicationService wiki,
            JwtPrincipalContextFactory principals
    ) {
        this.wiki = wiki;
        this.principals = principals;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WikiApi.PageDetail compile(
            @Valid @RequestBody WikiApi.CompileRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return wiki.compile(principals.create(jwt), request);
    }

    @GetMapping
    public List<WikiApi.PageSummary> list(
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) KnowledgePageStatus status,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return wiki.list(principals.create(jwt), spaceId, status);
    }

    @GetMapping("/{pageId}")
    public WikiApi.PageDetail detail(
            @PathVariable UUID pageId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return wiki.detail(principals.create(jwt), pageId);
    }

    @PostMapping("/{pageId}/submit-review")
    public WikiApi.PageDetail submitReview(
            @PathVariable UUID pageId,
            @Valid @RequestBody WikiApi.TransitionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return transition(pageId, request, KnowledgePageStatus.IN_REVIEW, jwt);
    }

    @PostMapping("/{pageId}/reject")
    public WikiApi.PageDetail reject(
            @PathVariable UUID pageId,
            @Valid @RequestBody WikiApi.TransitionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return transition(pageId, request, KnowledgePageStatus.DRAFT, jwt);
    }

    @PostMapping("/{pageId}/publish")
    public WikiApi.PageDetail publish(
            @PathVariable UUID pageId,
            @Valid @RequestBody WikiApi.TransitionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return transition(pageId, request, KnowledgePageStatus.PUBLISHED, jwt);
    }

    @PostMapping("/{pageId}/archive")
    public WikiApi.PageDetail archive(
            @PathVariable UUID pageId,
            @Valid @RequestBody WikiApi.TransitionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return transition(pageId, request, KnowledgePageStatus.ARCHIVED, jwt);
    }

    private WikiApi.PageDetail transition(
            UUID pageId,
            WikiApi.TransitionRequest request,
            KnowledgePageStatus target,
            Jwt jwt
    ) {
        return wiki.transition(
                principals.create(jwt),
                pageId,
                request.expectedVersion(),
                target
        );
    }
}
