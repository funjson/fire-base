package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.DocumentLifecycleService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Admin lifecycle commands for tenant-bound knowledge documents. */
@RestController
@RequestMapping("/api/v1/documents")
public final class DocumentLifecycleController {
    private final DocumentLifecycleService lifecycle;
    private final JwtPrincipalContextFactory principals;

    public DocumentLifecycleController(
            DocumentLifecycleService lifecycle,
            JwtPrincipalContextFactory principals
    ) {
        this.lifecycle = lifecycle;
        this.principals = principals;
    }

    @PatchMapping("/{documentId}/status")
    public DocumentLifecycleApi.Response transition(
            @PathVariable UUID documentId,
            @Valid @RequestBody DocumentLifecycleApi.Request request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return lifecycle.transition(principals.create(jwt), documentId, request);
    }
}
