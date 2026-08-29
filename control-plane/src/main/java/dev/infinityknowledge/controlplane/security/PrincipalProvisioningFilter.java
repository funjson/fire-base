package dev.infinityknowledge.controlplane.security;

import dev.infinityknowledge.controlplane.application.governance.KnowledgeGovernanceService;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * 在首个已认证请求中幂等登记业务主体，Keycloak 仍只负责认证。
 */
@Component
public final class PrincipalProvisioningFilter extends OncePerRequestFilter {

    private final JwtPrincipalContextFactory principalFactory;
    private final KnowledgeGovernanceService governance;

    public PrincipalProvisioningFilter(
            JwtPrincipalContextFactory principalFactory,
            KnowledgeGovernanceService governance
    ) {
        this.principalFactory = Objects.requireNonNull(
                principalFactory,
                "principalFactory must not be null"
        );
        this.governance = Objects.requireNonNull(
                governance,
                "governance must not be null"
        );
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && authentication.isAuthenticated()) {
            Jwt jwt = token.getToken();
            PrincipalContext principal;
            try {
                principal = principalFactory.create(jwt);
            } catch (IllegalArgumentException invalidClaims) {
                SecurityContextHolder.clearContext();
                response.sendError(
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Required identity claims are missing or invalid"
                );
                return;
            }
            try {
                governance.ensurePrincipal(principal);
            } catch (KnowledgeAccessDeniedException denied) {
                SecurityContextHolder.clearContext();
                response.sendError(
                        HttpServletResponse.SC_FORBIDDEN,
                        "The knowledge principal is not active"
                );
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
