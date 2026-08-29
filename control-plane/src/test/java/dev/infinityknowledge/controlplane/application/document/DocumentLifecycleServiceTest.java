package dev.infinityknowledge.controlplane.application.document;

import dev.infinityknowledge.controlplane.api.document.DocumentLifecycleApi;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.management.DocumentLifecycleStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");

    @Test
    void performsTenantBoundOptimisticTransition() {
        DocumentLifecycleStore store = mock(DocumentLifecycleStore.class);
        UUID documentId = UUID.randomUUID();
        when(store.transition(
                new TenantId("tenant-a"), documentId, 3,
                DocumentStatus.ARCHIVED, NOW
        )).thenReturn(Optional.of(new DocumentLifecycleStore.DocumentState(
                documentId, "engineering", DocumentStatus.ARCHIVED,
                4, NOW, true
        )));
        var service = new DocumentLifecycleService(
                store, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        var response = service.transition(
                admin(), documentId, new DocumentLifecycleApi.Request("ARCHIVED", 3)
        );

        assertEquals("ARCHIVED", response.status());
        assertEquals(4, response.version());
    }

    @Test
    void rejectsReaderBeforeCallingStore() {
        DocumentLifecycleStore store = mock(DocumentLifecycleStore.class);
        var service = new DocumentLifecycleService(
                store, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThrows(AccessDeniedException.class, () -> service.transition(
                reader(), UUID.randomUUID(),
                new DocumentLifecycleApi.Request("DELETED", 1)
        ));

        verify(store, never()).transition(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
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
                new PrincipalId("principal-a"),
                roles,
                Set.of(),
                false
        );
    }
}
