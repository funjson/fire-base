package dev.infinityknowledge.controlplane.application.document;

import dev.infinityknowledge.controlplane.api.document.DocumentLifecycleApi;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.spi.management.DocumentLifecycleStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** 执行管理 API 提交的租户绑定文档生命周期命令。 */
@Service
public final class DocumentLifecycleService {
    private final DocumentLifecycleStore store;
    private final Clock clock;

    public DocumentLifecycleService(DocumentLifecycleStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public DocumentLifecycleApi.Response transition(
            PrincipalContext principal,
            UUID documentId,
            DocumentLifecycleApi.Request request
    ) {
        requireAdmin(principal);
        DocumentStatus targetStatus = DocumentStatus.valueOf(request.status());
        DocumentLifecycleStore.DocumentState state = store.transition(
                principal.tenantId(),
                documentId,
                request.expectedVersion(),
                targetStatus,
                clock.instant()
        ).orElseThrow(KnowledgeDocumentNotFoundException::new);
        return new DocumentLifecycleApi.Response(
                state.documentId(),
                state.spaceId(),
                state.status().name(),
                state.version(),
                state.updatedAt(),
                state.changed()
        );
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal()
                && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
