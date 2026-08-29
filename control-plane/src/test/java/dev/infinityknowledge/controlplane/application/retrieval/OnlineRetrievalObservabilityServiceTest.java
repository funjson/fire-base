package dev.infinityknowledge.controlplane.application.retrieval;

import dev.infinityknowledge.controlplane.config.RetrievalProperties;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityReader;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalOverview;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证在线观测统一使用全量授权 Space、固定用途和成熟度宽限。 */
class OnlineRetrievalObservabilityServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-29T08:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE_A = new KnowledgeSpaceId("space-a");
    private static final KnowledgeSpaceId SPACE_B = new KnowledgeSpaceId("space-b");

    @Test
    void keepsFullAuthorizedScopeWhenFilteringOneVisitedSpace() {
        OnlineRetrievalObservabilityReader reader = mock(
                OnlineRetrievalObservabilityReader.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(accessPolicy.resolve(principal(), Set.of())).thenReturn(
                AccessScope.all(TENANT, Set.of(SPACE_A, SPACE_B))
        );
        when(reader.overview(org.mockito.ArgumentMatchers.any())).thenReturn(emptyOverview());

        service(reader, accessPolicy).overview(
                principal(),
                new OnlineRetrievalObservabilityService.Filters(
                        NOW.minus(Duration.ofHours(1)),
                        NOW,
                        SPACE_A.value(),
                        null,
                        null
                )
        );

        ArgumentCaptor<OnlineRetrievalObservabilityQuery> query = ArgumentCaptor.forClass(
                OnlineRetrievalObservabilityQuery.class
        );
        verify(reader).overview(query.capture());
        assertEquals(Set.of(SPACE_A, SPACE_B), query.getValue().authorizedSpaceIds());
        assertEquals(SPACE_A, query.getValue().spaceId().orElseThrow());
        assertEquals(NOW.minusSeconds(65), query.getValue().maturityCutoff());
        verify(accessPolicy).resolve(principal(), Set.of());
    }

    @Test
    void rejectsDocumentWhitelistScopeToAvoidAggregateSideChannels() {
        OnlineRetrievalObservabilityReader reader = mock(
                OnlineRetrievalObservabilityReader.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(accessPolicy.resolve(principal(), Set.of())).thenReturn(
                AccessScope.only(TENANT, Set.of(SPACE_A), Set.of("document-a"))
        );

        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> service(reader, accessPolicy).overview(
                        principal(),
                        new OnlineRetrievalObservabilityService.Filters(
                                NOW.minusSeconds(60), NOW, null, null, null
                        )
                )
        );
        verifyNoInteractions(reader);
    }

    @Test
    void clampsSmallClientClockSkewButRejectsClearlyFutureWindow() {
        OnlineRetrievalObservabilityReader reader = mock(
                OnlineRetrievalObservabilityReader.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(accessPolicy.resolve(principal(), Set.of())).thenReturn(
                AccessScope.all(TENANT, Set.of(SPACE_A))
        );
        when(reader.overview(org.mockito.ArgumentMatchers.any())).thenReturn(emptyOverview());
        OnlineRetrievalObservabilityService service = service(reader, accessPolicy);

        service.overview(
                principal(),
                new OnlineRetrievalObservabilityService.Filters(
                        NOW.minusSeconds(60), NOW.plusSeconds(30), null, null, null
                )
        );

        ArgumentCaptor<OnlineRetrievalObservabilityQuery> query = ArgumentCaptor.forClass(
                OnlineRetrievalObservabilityQuery.class
        );
        verify(reader).overview(query.capture());
        assertEquals(NOW, query.getValue().to());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.overview(
                        principal(),
                        new OnlineRetrievalObservabilityService.Filters(
                                NOW.minusSeconds(60), NOW.plusSeconds(121), null, null, null
                        )
                )
        );
    }

    private static OnlineRetrievalObservabilityService service(
            OnlineRetrievalObservabilityReader reader,
            AccessPolicy accessPolicy
    ) {
        return new OnlineRetrievalObservabilityService(
                reader,
                accessPolicy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new RetrievalProperties(
                        RetrievalProperties.Mode.STANDARD,
                        2,
                        60,
                        0.7D,
                        2,
                        10,
                        Duration.ofSeconds(35),
                        Duration.ofSeconds(30)
                )
        );
    }

    private static OnlineRetrievalOverview emptyOverview() {
        var emptyRate = OnlineRetrievalOverview.Rate.of(0L, 0L);
        return new OnlineRetrievalOverview(
                NOW.minusSeconds(60),
                NOW,
                OnlineRetrievalObservabilityQuery.TimeBucketGranularity.MINUTE,
                0L,
                List.of(),
                List.of(),
                emptyRate,
                emptyRate,
                OnlineRetrievalOverview.Percentiles.empty(),
                emptyRate,
                emptyRate,
                emptyRate,
                emptyRate,
                emptyRate,
                emptyRate,
                List.of()
        );
    }

    private static PrincipalContext principal() {
        return new PrincipalContext(
                TENANT,
                new PrincipalId("reader-a"),
                Set.of("knowledge-reader"),
                Set.of(),
                false
        );
    }
}
