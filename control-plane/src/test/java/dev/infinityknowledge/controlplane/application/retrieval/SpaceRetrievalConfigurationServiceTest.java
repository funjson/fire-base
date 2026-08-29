package dev.infinityknowledge.controlplane.application.retrieval;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Space 检索配置的授权读取、追加修订和并发保护。 */
class SpaceRetrievalConfigurationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");
    private static final KnowledgeSpaceId SPACE_ID = new KnowledgeSpaceId("engineering");

    @Test
    void readsCurrentConfigurationOnlyAfterSpaceAuthorization() {
        SpaceRetrievalConfigurationStore store = mock(
                SpaceRetrievalConfigurationStore.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        SpaceRetrievalConfiguration current = revision(1L, baseline());
        when(accessPolicy.resolve(reader(), Set.of(SPACE_ID))).thenReturn(
                AccessScope.all(reader().tenantId(), Set.of(SPACE_ID))
        );
        when(store.findCurrent(reader().tenantId(), SPACE_ID)).thenReturn(
                Optional.of(current)
        );

        assertSame(current, service(store, accessPolicy).current(reader(), SPACE_ID.value()));
    }

    @Test
    void rejectsUnreadableSpaceBeforeLookingUpConfiguration() {
        SpaceRetrievalConfigurationStore store = mock(
                SpaceRetrievalConfigurationStore.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(accessPolicy.resolve(reader(), Set.of(SPACE_ID))).thenReturn(
                AccessScope.denyAll(reader().tenantId())
        );

        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> service(store, accessPolicy).current(reader(), SPACE_ID.value())
        );

        verifyNoInteractions(store);
    }

    @Test
    void appendsNextImmutableRevisionForAdministrator() {
        SpaceRetrievalConfigurationStore store = mock(
                SpaceRetrievalConfigurationStore.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        RetrievalConfiguration requested = configurationWithRrfConstant(80);
        when(store.findCurrent(admin().tenantId(), SPACE_ID)).thenReturn(
                Optional.of(revision(1L, baseline()))
        );
        when(store.appendAndActivate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L)
        )).thenReturn(SpaceRetrievalConfigurationStore.ActivationOutcome.ACTIVATED);

        SpaceRetrievalConfiguration updated = service(store, accessPolicy).update(
                admin(),
                SPACE_ID.value(),
                1L,
                requested
        );

        assertEquals(2L, updated.revision());
        assertEquals(requested.fingerprint(), updated.fingerprint());
        assertEquals(admin().principalId(), updated.createdBy());
        assertEquals(NOW, updated.createdAt());
        ArgumentCaptor<SpaceRetrievalConfiguration> saved = ArgumentCaptor.forClass(
                SpaceRetrievalConfiguration.class
        );
        verify(store).appendAndActivate(saved.capture(), org.mockito.ArgumentMatchers.eq(1L));
        assertEquals(updated, saved.getValue());
        verifyNoInteractions(accessPolicy);
    }

    @Test
    void reportsConflictWhenExpectedRevisionIsStale() {
        SpaceRetrievalConfigurationStore store = mock(
                SpaceRetrievalConfigurationStore.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(store.findCurrent(admin().tenantId(), SPACE_ID)).thenReturn(
                Optional.of(revision(3L, configurationWithRrfConstant(90)))
        );

        assertThrows(
                SpaceRetrievalConfigurationConflictException.class,
                () -> service(store, accessPolicy).update(
                        admin(),
                        SPACE_ID.value(),
                        1L,
                        configurationWithRrfConstant(80)
                )
        );

        verify(store, never()).appendAndActivate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void returnsAlreadyWrittenNextRevisionForAnIdenticalRetry() {
        SpaceRetrievalConfigurationStore store = mock(
                SpaceRetrievalConfigurationStore.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        RetrievalConfiguration requested = configurationWithRrfConstant(80);
        SpaceRetrievalConfiguration existing = revision(2L, requested);
        when(store.findCurrent(admin().tenantId(), SPACE_ID)).thenReturn(
                Optional.of(existing)
        );

        assertSame(existing, service(store, accessPolicy).update(
                admin(),
                SPACE_ID.value(),
                1L,
                requested
        ));

        verify(store, never()).appendAndActivate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void doesNotCreateANewRevisionWhenCurrentContentIsUnchanged() {
        SpaceRetrievalConfigurationStore store = mock(
                SpaceRetrievalConfigurationStore.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        SpaceRetrievalConfiguration existing = revision(2L, baseline());
        when(store.findCurrent(admin().tenantId(), SPACE_ID)).thenReturn(
                Optional.of(existing)
        );

        assertSame(existing, service(store, accessPolicy).update(
                admin(),
                SPACE_ID.value(),
                2L,
                baseline()
        ));

        verify(store, never()).appendAndActivate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void rejectsWritesFromNonAdministrator() {
        SpaceRetrievalConfigurationStore store = mock(
                SpaceRetrievalConfigurationStore.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);

        assertThrows(
                AccessDeniedException.class,
                () -> service(store, accessPolicy).update(
                        reader(),
                        SPACE_ID.value(),
                        1L,
                        baseline()
                )
        );

        verifyNoInteractions(store, accessPolicy);
    }

    @Test
    void returnsAuthorizedHistoryInStoreOrder() {
        SpaceRetrievalConfigurationStore store = mock(
                SpaceRetrievalConfigurationStore.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        List<SpaceRetrievalConfiguration> revisions = List.of(
                revision(2L, configurationWithRrfConstant(80)),
                revision(1L, baseline())
        );
        when(accessPolicy.resolve(reader(), Set.of(SPACE_ID))).thenReturn(
                AccessScope.all(reader().tenantId(), Set.of(SPACE_ID))
        );
        when(store.history(reader().tenantId(), SPACE_ID, 20)).thenReturn(revisions);

        assertEquals(
                revisions,
                service(store, accessPolicy).history(reader(), SPACE_ID.value(), 20)
        );
    }

    private static SpaceRetrievalConfigurationService service(
            SpaceRetrievalConfigurationStore store,
            AccessPolicy accessPolicy
    ) {
        return new SpaceRetrievalConfigurationService(
                store,
                accessPolicy,
                new RetrievalConfigurationResolver(),
                RetrievalConfigurationHardLimits.conservativeDefaults(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static RetrievalConfiguration baseline() {
        return RetrievalConfiguration.deterministicBaseline();
    }

    private static RetrievalConfiguration configurationWithRrfConstant(int constant) {
        RetrievalConfiguration baseline = baseline();
        return new RetrievalConfiguration(
                baseline.firstRound(),
                new RetrievalConfiguration.Branches(
                        baseline.branches().maximumVariantsPerAttempt(),
                        baseline.branches().maximumRetrievalBranches(),
                        constant,
                        baseline.branches().channels()
                ),
                baseline.reranker(),
                baseline.coverage(),
                baseline.maximumRetrievalAttempts(),
                baseline.chainNodeEnables(),
                baseline.crossSpace()
        );
    }

    private static SpaceRetrievalConfiguration revision(
            long revision,
            RetrievalConfiguration configuration
    ) {
        return SpaceRetrievalConfiguration.create(
                admin().tenantId(),
                SPACE_ID,
                revision,
                configuration,
                admin().principalId(),
                NOW
        );
    }

    private static PrincipalContext admin() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin-a"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }

    private static PrincipalContext reader() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("reader-a"),
                Set.of("knowledge-reader"),
                Set.of(),
                false
        );
    }
}
