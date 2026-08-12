package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.controlplane.application.AuditApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant administrator endpoint for security-safe mutation audit events. */
@RestController
@RequestMapping("/api/v1/admin/audit-events")
public final class AuditController {
    private final AuditApplicationService audit;
    private final JwtPrincipalContextFactory principals;

    public AuditController(
            AuditApplicationService audit,
            JwtPrincipalContextFactory principals
    ) {
        this.audit = audit;
        this.principals = principals;
    }

    @GetMapping
    public AuditApi.Page find(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return audit.find(principals.create(jwt), limit, offset);
    }
}
