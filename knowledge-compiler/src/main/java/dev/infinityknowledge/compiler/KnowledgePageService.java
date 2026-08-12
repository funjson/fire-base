package dev.infinityknowledge.compiler;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.wiki.KnowledgePageId;
import dev.infinityknowledge.domain.wiki.KnowledgePageStatus;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;
import dev.infinityknowledge.spi.wiki.KnowledgePageStore;

import java.time.Instant;
import java.util.Objects;

/** Small application-neutral use case for compilation and governed publication. */
public final class KnowledgePageService {
    private final KnowledgePageCompiler compiler;
    private final KnowledgePageStore store;

    public KnowledgePageService(KnowledgePageCompiler compiler, KnowledgePageStore store) {
        this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public KnowledgePageStore.PageSnapshot compileDraft(
            KnowledgePageCompiler.CompilationRequest request,
            PrincipalId actor,
            Instant now
    ) {
        Objects.requireNonNull(request, "request must not be null");
        return store.saveDraft(new KnowledgePageStore.DraftCommand(
                request.tenantId(),
                request.spaceId(),
                request.slug(),
                request.title(),
                compiler.compile(request),
                Objects.requireNonNull(actor, "actor must not be null"),
                Objects.requireNonNull(now, "now must not be null")
        ));
    }

    public KnowledgePageStore.PageSnapshot transition(
            TenantId tenantId,
            KnowledgePageId pageId,
            long expectedVersion,
            KnowledgePageStatus targetStatus,
            PrincipalId actor,
            Instant now
    ) {
        return store.transition(new KnowledgePageStore.TransitionCommand(
                tenantId,
                pageId,
                expectedVersion,
                targetStatus,
                actor,
                now
        ));
    }
}
