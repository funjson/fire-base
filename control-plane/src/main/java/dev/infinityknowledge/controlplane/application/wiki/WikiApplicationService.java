package dev.infinityknowledge.controlplane.application.wiki;

import dev.infinityknowledge.compiler.KnowledgePageService;
import dev.infinityknowledge.controlplane.api.wiki.WikiApi;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.wiki.KnowledgePageId;
import dev.infinityknowledge.domain.wiki.KnowledgePageRevision;
import dev.infinityknowledge.domain.wiki.KnowledgePageStatus;
import dev.infinityknowledge.domain.wiki.PageSourceReference;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;
import dev.infinityknowledge.spi.wiki.KnowledgePageSourceStore;
import dev.infinityknowledge.spi.wiki.KnowledgePageStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 面向 Wiki 编译与审核的轻量、租户安全应用边界。 */
@Service
public final class WikiApplicationService {
    private static final int MAX_SOURCE_CHARACTERS = 120_000;
    private final KnowledgePageService pages;
    private final KnowledgePageStore store;
    private final KnowledgePageSourceStore sources;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public WikiApplicationService(
            KnowledgePageService pages,
            KnowledgePageStore store,
            KnowledgePageSourceStore sources,
            AccessPolicy accessPolicy,
            Clock clock
    ) {
        this.pages = Objects.requireNonNull(pages, "pages must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public WikiApi.PageDetail compile(
            PrincipalContext principal,
            WikiApi.CompileRequest request
    ) {
        requireAdmin(principal);
        Objects.requireNonNull(request, "request must not be null");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId(request.spaceId());
        List<KnowledgePageSourceStore.SourceSelection> selections = request.sources().stream()
                .map(source -> new KnowledgePageSourceStore.SourceSelection(
                        new DocumentId(source.documentId()),
                        source.revisionId(),
                        source.chunkId()
                ))
                .toList();
        AccessScope scope = requireAccess(principal, spaceId);
        selections.forEach(source -> requireDocument(scope, source.documentId()));
        List<KnowledgePageCompiler.CompilationSource> compilationSources = sources.load(
                principal.tenantId(),
                spaceId,
                selections
        ).stream().map(source -> new KnowledgePageCompiler.CompilationSource(
                source.chunk(),
                source.authority()
        )).toList();
        long sourceCharacters = compilationSources.stream()
                .mapToLong(source -> source.chunk().content().length())
                .sum();
        if (sourceCharacters > MAX_SOURCE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "page source content exceeds the 120000 character compilation budget"
            );
        }
        KnowledgePageStore.PageSnapshot saved = pages.compileDraft(
                new KnowledgePageCompiler.CompilationRequest(
                        principal.tenantId(),
                        spaceId,
                        request.slug(),
                        request.title(),
                        compilationSources
                ),
                principal.principalId(),
                clock.instant()
        );
        return view(principal, saved);
    }

    public List<WikiApi.PageSummary> list(
            PrincipalContext principal,
            String requestedSpaceId,
            KnowledgePageStatus status
    ) {
        requireAdmin(principal);
        Set<KnowledgeSpaceId> requested = requestedSpaceId == null
                || requestedSpaceId.isBlank()
                ? Set.of()
                : Set.of(new KnowledgeSpaceId(requestedSpaceId));
        AccessScope scope = resolve(principal, requested);
        return store.findAll(principal.tenantId(), scope.spaceIds(), status).stream()
                .map(snapshot -> summary(snapshot, snapshot.revision().sources().size()))
                .toList();
    }

    public WikiApi.PageDetail detail(PrincipalContext principal, UUID pageId) {
        requireAdmin(principal);
        KnowledgePageStore.PageSnapshot latest = store.findById(
                principal.tenantId(),
                new KnowledgePageId(pageId)
        ).orElseThrow(() -> new IllegalArgumentException("knowledge page does not exist"));
        requireAccess(principal, latest.page().spaceId());
        return view(principal, latest);
    }

    private WikiApi.PageDetail view(
            PrincipalContext principal,
            KnowledgePageStore.PageSnapshot latest
    ) {
        KnowledgePageRevision active = latest.page().activeRevisionId() == null
                ? null
                : store.findRevision(
                        principal.tenantId(),
                        latest.page().id(),
                        latest.page().activeRevisionId()
                ).orElseThrow(() -> new IllegalStateException(
                        "active knowledge page revision does not exist"
                ));
        return new WikiApi.PageDetail(
                summary(latest, latest.revision().sources().size()),
                revision(latest.revision()),
                active == null ? null : revision(active),
                active != null && !active.id().equals(latest.revision().id())
        );
    }

    public WikiApi.PageDetail transition(
            PrincipalContext principal,
            UUID pageId,
            long expectedVersion,
            KnowledgePageStatus target
    ) {
        requireAdmin(principal);
        KnowledgePageId id = new KnowledgePageId(pageId);
        KnowledgePageStore.PageSnapshot current = store.findById(principal.tenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("knowledge page does not exist"));
        requireAccess(principal, current.page().spaceId());
        KnowledgePageStore.PageSnapshot transitioned = pages.transition(
                principal.tenantId(),
                id,
                expectedVersion,
                target,
                principal.principalId(),
                clock.instant()
        );
        return view(principal, transitioned);
    }

    private AccessScope requireAccess(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId
    ) {
        AccessScope scope = resolve(principal, Set.of(spaceId));
        if (!scope.allowsSpace(spaceId)) {
            throw new KnowledgeAccessDeniedException("knowledge space is not accessible");
        }
        return scope;
    }

    private AccessScope resolve(
            PrincipalContext principal,
            Set<KnowledgeSpaceId> requestedSpaces
    ) {
        AccessScope scope = accessPolicy.resolve(principal, requestedSpaces);
        if (!principal.tenantId().equals(scope.tenantId())) {
            throw new SecurityException("access policy returned a different tenant");
        }
        return scope;
    }

    private static void requireDocument(AccessScope scope, DocumentId documentId) {
        if (!scope.allowsDocument(documentId.value().toString())) {
            throw new KnowledgeAccessDeniedException("knowledge document is not accessible");
        }
    }

    private static WikiApi.PageSummary summary(
            KnowledgePageStore.PageSnapshot snapshot,
            int sourceCount
    ) {
        var page = snapshot.page();
        return new WikiApi.PageSummary(
                page.id().value(),
                page.spaceId().value(),
                page.slug(),
                page.title(),
                page.status(),
                page.latestRevisionId(),
                page.activeRevisionId(),
                page.version(),
                sourceCount,
                page.updatedAt()
        );
    }

    private static WikiApi.Revision revision(KnowledgePageRevision value) {
        return new WikiApi.Revision(
                value.id(),
                value.revisionNumber(),
                value.summary(),
                value.markdown(),
                value.sources().stream().map(WikiApplicationService::source).toList(),
                value.contentHash(),
                value.compilerVersion(),
                value.generatedBy(),
                value.createdAt()
        );
    }

    private static WikiApi.SourceReference source(PageSourceReference value) {
        return new WikiApi.SourceReference(
                value.documentId().value(),
                value.revisionId(),
                value.chunkId(),
                value.sectionPath(),
                value.contentHash(),
                value.authority()
        );
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
