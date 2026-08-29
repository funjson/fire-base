package dev.infinityknowledge.controlplane.application.wiki;

import dev.infinityknowledge.compiler.KnowledgePageService;
import dev.infinityknowledge.controlplane.api.wiki.WikiApi;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;
import dev.infinityknowledge.spi.wiki.KnowledgePageSourceStore;
import dev.infinityknowledge.spi.wiki.KnowledgePageStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiApplicationServiceTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T00:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void rejectsSelectedDocumentOutsideResolvedAclBeforeLoadingContent() {
        KnowledgePageCompiler compiler = mock(KnowledgePageCompiler.class);
        KnowledgePageStore store = mock(KnowledgePageStore.class);
        KnowledgePageSourceStore sourceStore = mock(KnowledgePageSourceStore.class);
        UUID permittedDocument = UUID.randomUUID();
        var service = service(
                compiler,
                store,
                sourceStore,
                AccessScope.only(
                        TENANT,
                        Set.of(SPACE),
                        Set.of(permittedDocument.toString())
                )
        );
        WikiApi.CompileRequest request = new WikiApi.CompileRequest(
                SPACE.value(),
                "order-service",
                "订单服务",
                List.of(new WikiApi.SourceSelection(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()
                ))
        );

        assertThrows(
                dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException.class,
                () -> service.compile(admin(), request)
        );
        verify(sourceStore, never()).load(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void listIsRestrictedToSpacesReturnedByAccessPolicy() {
        KnowledgePageCompiler compiler = mock(KnowledgePageCompiler.class);
        KnowledgePageStore store = mock(KnowledgePageStore.class);
        KnowledgePageSourceStore sourceStore = mock(KnowledgePageSourceStore.class);
        var service = service(
                compiler,
                store,
                sourceStore,
                AccessScope.all(TENANT, Set.of(SPACE))
        );
        when(store.findAll(TENANT, Set.of(SPACE), null)).thenReturn(List.of());

        service.list(admin(), null, null);

        verify(store).findAll(TENANT, Set.of(SPACE), null);
    }

    @Test
    void requiresKnowledgeAdminBeforeResolvingAcl() {
        KnowledgePageStore store = mock(KnowledgePageStore.class);
        var service = service(
                mock(KnowledgePageCompiler.class),
                store,
                mock(KnowledgePageSourceStore.class),
                AccessScope.all(TENANT, Set.of(SPACE))
        );
        PrincipalContext reader = new PrincipalContext(
                TENANT,
                new PrincipalId("reader"),
                Set.of("knowledge-reader"),
                Set.of(),
                false
        );

        assertThrows(AccessDeniedException.class, () -> service.list(reader, null, null));
        verify(store, never()).findAll(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private static WikiApplicationService service(
            KnowledgePageCompiler compiler,
            KnowledgePageStore store,
            KnowledgePageSourceStore sourceStore,
            AccessScope scope
    ) {
        return new WikiApplicationService(
                new KnowledgePageService(compiler, store),
                store,
                sourceStore,
                (principal, requested) -> scope,
                CLOCK
        );
    }

    private static PrincipalContext admin() {
        return new PrincipalContext(
                TENANT,
                new PrincipalId("admin"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }
}
