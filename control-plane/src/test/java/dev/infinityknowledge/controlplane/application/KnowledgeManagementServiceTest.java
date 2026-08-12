package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.management.KnowledgeAdministrationStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeManagementServiceTest {

    @Test
    void returnsTenantBoundRevisionHistory() {
        KnowledgeAdministrationStore store = mock(KnowledgeAdministrationStore.class);
        KnowledgeManagementService service = new KnowledgeManagementService(store);
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        when(store.revisions(new TenantId("tenant-a"), documentId)).thenReturn(List.of(
                new KnowledgeAdministrationStore.Revision(
                        revisionId,
                        2,
                        "content-hash",
                        "text/markdown",
                        "zh-CN",
                        "markdown-v1",
                        Instant.parse("2026-08-11T01:00:00Z"),
                        true,
                        3
                )
        ));

        var result = service.revisions(admin(), documentId);

        assertEquals(1, result.size());
        assertEquals(revisionId, result.getFirst().revisionId());
        assertEquals(2, result.getFirst().revisionNumber());
        assertEquals(3, result.getFirst().chunkCount());
        verify(store).revisions(new TenantId("tenant-a"), documentId);
    }

    @Test
    void returnsChunksOnlyForRequestedDocumentRevisionAndTenant() {
        KnowledgeAdministrationStore store = mock(KnowledgeAdministrationStore.class);
        KnowledgeManagementService service = new KnowledgeManagementService(store);
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(store.chunks(new TenantId("tenant-a"), documentId, revisionId))
                .thenReturn(List.of(new KnowledgeAdministrationStore.Chunk(
                        chunkId,
                        0,
                        List.of("Overview"),
                        "Revision content",
                        "chunk-hash"
                )));

        var result = service.chunks(admin(), documentId, revisionId);

        assertEquals(1, result.size());
        assertEquals(chunkId, result.getFirst().id());
        assertEquals(List.of("Overview"), result.getFirst().sectionPath());
        verify(store).chunks(new TenantId("tenant-a"), documentId, revisionId);
    }

    @Test
    void rejectsRevisionHistoryForNonAdministratorBeforeStoreAccess() {
        KnowledgeAdministrationStore store = mock(KnowledgeAdministrationStore.class);
        KnowledgeManagementService service = new KnowledgeManagementService(store);

        assertThrows(
                AccessDeniedException.class,
                () -> service.revisions(reader(), UUID.randomUUID())
        );
        verifyNoInteractions(store);
    }

    private static PrincipalContext admin() {
        return principal(Set.of("knowledge-admin"));
    }

    private static PrincipalContext reader() {
        return principal(Set.of("knowledge-reader"));
    }

    private static PrincipalContext principal(Set<String> roles) {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("user-1"),
                roles,
                Set.of(),
                false
        );
    }
}
