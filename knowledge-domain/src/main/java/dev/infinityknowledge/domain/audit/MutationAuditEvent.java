package dev.infinityknowledge.domain.audit;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Security-safe audit metadata for one mutating HTTP request.
 *
 * <p>The model deliberately has no request body, query string, authorization header, or
 * knowledge-content field. The route must be a normalized Spring route pattern rather than the
 * concrete request URI.</p>
 */
public record MutationAuditEvent(
        UUID id,
        TenantId tenantId,
        PrincipalId principalId,
        UUID requestId,
        String httpMethod,
        String routePattern,
        String action,
        int responseStatus,
        AuditOutcome outcome,
        Duration duration,
        Instant createdAt
) {
    private static final Set<String> MUTATING_METHODS = Set.of(
            "POST", "PUT", "PATCH", "DELETE"
    );

    public MutationAuditEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(principalId, "principalId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        httpMethod = DomainChecks.requiredText(httpMethod, "httpMethod", 16)
                .toUpperCase(Locale.ROOT);
        if (!MUTATING_METHODS.contains(httpMethod)) {
            throw new IllegalArgumentException("httpMethod must mutate server state");
        }
        routePattern = DomainChecks.requiredText(routePattern, "routePattern", 256);
        if (!routePattern.startsWith("/api/v1/")) {
            throw new IllegalArgumentException("routePattern must be an /api/v1 route");
        }
        action = DomainChecks.requiredText(action, "action", 320);
        if (responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException("responseStatus must be a valid HTTP status");
        }
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
