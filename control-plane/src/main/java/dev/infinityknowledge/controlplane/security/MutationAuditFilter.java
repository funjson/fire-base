package dev.infinityknowledge.controlplane.security;

import dev.infinityknowledge.controlplane.api.RequestCorrelationFilter;
import dev.infinityknowledge.domain.audit.AuditOutcome;
import dev.infinityknowledge.domain.audit.MutationAuditEvent;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.spi.audit.AuditStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Records safe metadata for authenticated mutating API requests. */
@Component
public final class MutationAuditFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MutationAuditFilter.class);
    private static final Set<String> MUTATING_METHODS = Set.of(
            "POST", "PUT", "PATCH", "DELETE"
    );
    private static final String UNMATCHED_ROUTE = "/api/v1/unmatched";
    private final AuditStore auditStore;
    private final JwtPrincipalContextFactory principals;
    private final Clock clock;

    public MutationAuditFilter(
            AuditStore auditStore,
            JwtPrincipalContextFactory principals,
            Clock clock
    ) {
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore must not be null");
        this.principals = Objects.requireNonNull(principals, "principals must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationPath(request);
        return !MUTATING_METHODS.contains(request.getMethod())
                || !path.startsWith("/api/v1/")
                || isReadOnlyPost(request.getMethod(), path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        PrincipalContext principal = currentPrincipal();
        long startedAt = System.nanoTime();
        boolean unhandledFailure = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            unhandledFailure = true;
            throw failure;
        } finally {
            if (principal != null) {
                appendSafely(request, response, principal, startedAt, unhandledFailure);
            }
        }
    }

    private void appendSafely(
            HttpServletRequest request,
            HttpServletResponse response,
            PrincipalContext principal,
            long startedAt,
            boolean unhandledFailure
    ) {
        String route = routePattern(request);
        int status = response.getStatus();
        if (unhandledFailure && status < 400) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        try {
            auditStore.append(new MutationAuditEvent(
                    UUID.randomUUID(),
                    principal.tenantId(),
                    principal.principalId(),
                    requestId(request),
                    request.getMethod(),
                    route,
                    request.getMethod() + " " + route,
                    status,
                    status < 400 ? AuditOutcome.SUCCEEDED : AuditOutcome.FAILED,
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt)),
                    clock.instant()
            ));
        } catch (RuntimeException persistenceFailure) {
            // Auditing must not change the already-computed business response.
            LOGGER.error(
                    "Mutation audit persistence failed: method={}, route={}, status={}, "
                            + "failureType={}",
                    request.getMethod(),
                    route,
                    status,
                    persistenceFailure.getClass().getSimpleName()
            );
        }
    }

    private PrincipalContext currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && authentication.isAuthenticated()) {
            try {
                return principals.create(token.getToken());
            } catch (IllegalArgumentException invalidClaims) {
                return null;
            }
        }
        return null;
    }

    private static UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestCorrelationFilter.ATTRIBUTE);
        return value instanceof UUID requestId ? requestId : UUID.randomUUID();
    }

    private static String routePattern(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (value instanceof String pattern
                && pattern.startsWith("/api/v1/")
                && pattern.length() <= 256
                && pattern.matches("[A-Za-z0-9_{}./*\\-]+")) {
            return pattern;
        }
        return UNMATCHED_ROUTE;
    }

    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context.isEmpty() ? uri : uri.substring(context.length());
    }

    private static boolean isReadOnlyPost(String method, String path) {
        if (!"POST".equals(method)) {
            return false;
        }
        return "/api/v1/knowledge/query".equals(path)
                || "/api/v1/admin/graph/search".equals(path)
                || path.matches("/api/v1/evaluations/datasets/[^/]+/compare");
    }
}
