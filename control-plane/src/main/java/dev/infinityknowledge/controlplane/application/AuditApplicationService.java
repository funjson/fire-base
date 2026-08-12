package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.AuditApi;
import dev.infinityknowledge.domain.audit.MutationAuditEvent;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.spi.audit.AuditStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Authorizes and maps tenant-scoped mutation audit reads. */
@Service
public final class AuditApplicationService {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_OFFSET = 1_000_000;
    private final AuditStore store;

    public AuditApplicationService(AuditStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public AuditApi.Page find(PrincipalContext principal, int limit, int offset) {
        requireAdmin(principal);
        if (limit < 1 || limit > MAX_LIMIT || offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("invalid audit page bounds");
        }
        var page = store.find(principal.tenantId(), limit, offset);
        return new AuditApi.Page(
                page.items().stream().map(AuditApplicationService::event).toList(),
                page.limit(),
                page.offset(),
                page.total()
        );
    }

    private static AuditApi.Event event(MutationAuditEvent value) {
        return new AuditApi.Event(
                value.id(),
                value.tenantId().value(),
                value.principalId().value(),
                value.requestId(),
                value.httpMethod(),
                value.routePattern(),
                value.action(),
                value.responseStatus(),
                value.outcome().name(),
                value.duration().toMillis(),
                value.createdAt()
        );
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
