package dev.infinityknowledge.controlplane.application.projection;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionJobStatusStore;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies tenant-scoped projection rebuild orchestration.
 */
class ProjectionAdministrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    @Test
    void rebuildsOnlyChannelsConfiguredInTheCurrentRuntime() {
        ProjectionJobStatusStore store = mock(ProjectionJobStatusStore.class);
        ProjectionExecutor keyword = mock(ProjectionExecutor.class);
        when(keyword.projectionType()).thenReturn(ProjectionType.KEYWORD);
        when(store.rebuildSpace(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                Set.of(ProjectionType.KEYWORD),
                NOW
        )).thenReturn(3);
        var service = new ProjectionAdministrationService(
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(keyword)
        );

        var result = service.rebuild(admin(), "engineering");

        assertEquals(3, result.jobs());
        assertEquals(Set.of(ProjectionType.KEYWORD), result.projectionTypes());
        verify(store).rebuildSpace(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                Set.of(ProjectionType.KEYWORD),
                NOW
        );
    }

    @Test
    void rejectsReadersBeforeAccessingProjectionState() {
        ProjectionJobStatusStore store = mock(ProjectionJobStatusStore.class);
        var service = new ProjectionAdministrationService(
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                List.of()
        );

        assertThrows(
                AccessDeniedException.class,
                () -> service.rebuild(reader(), "engineering")
        );
    }

    @Test
    void returnsAnEmptyResultWhenNoExternalProjectionChannelIsConfigured() {
        ProjectionJobStatusStore store = mock(ProjectionJobStatusStore.class);
        var service = new ProjectionAdministrationService(
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                List.of()
        );

        var result = service.rebuild(admin(), "engineering");

        assertEquals(0, result.jobs());
        assertEquals(Set.of(), result.projectionTypes());
        verify(store, never()).rebuildSpace(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                Set.of(),
                NOW
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
                new PrincipalId("principal-1"),
                roles,
                Set.of(),
                false
        );
    }
}
