package dev.infinityknowledge.controlplane.application.retrieval;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.ObservationCompleteness;
import dev.infinityknowledge.evaluation.observation.ObservationIncompleteReason;
import dev.infinityknowledge.evaluation.observation.VisitedRetrievalConfiguration;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReport;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReportReader;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证观测报告只能按 JWT 租户读取，并须通过全部 Space 的当前授权。 */
class RetrievalObservationReportServiceTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE_A = new KnowledgeSpaceId("space-a");
    private static final KnowledgeSpaceId SPACE_B = new KnowledgeSpaceId("space-b");
    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");
    private static final String CONFIG = "a".repeat(64);

    @Test
    void readsByPrincipalTenantAndAuthorizesEveryInvolvedSpace() {
        UUID requestId = UUID.randomUUID();
        RetrievalObservationReport report = report(TENANT, requestId, List.of(SPACE_A));
        RetrievalObservationReportReader reader = mock(
                RetrievalObservationReportReader.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(reader.findLatestExecution(TENANT, requestId)).thenReturn(Optional.of(report));
        when(accessPolicy.resolve(principal(), Set.of(SPACE_A))).thenReturn(
                AccessScope.all(TENANT, Set.of(SPACE_A))
        );

        assertSame(report, service(reader, accessPolicy).latest(principal(), requestId));

        verify(reader).findLatestExecution(TENANT, requestId);
        verify(accessPolicy).resolve(principal(), Set.of(SPACE_A));
    }

    @Test
    void returnsNotFoundForARequestOutsideTheJwtTenant() {
        UUID requestId = UUID.randomUUID();
        RetrievalObservationReportReader reader = mock(
                RetrievalObservationReportReader.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(reader.findLatestExecution(TENANT, requestId)).thenReturn(Optional.empty());

        assertThrows(
                RetrievalObservationReportNotFoundException.class,
                () -> service(reader, accessPolicy).latest(principal(), requestId)
        );

        verifyNoInteractions(accessPolicy);
    }

    @Test
    void rejectsAnAdapterThatReturnsAnotherTenant() {
        UUID requestId = UUID.randomUUID();
        RetrievalObservationReportReader reader = mock(
                RetrievalObservationReportReader.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(reader.findLatestExecution(TENANT, requestId)).thenReturn(Optional.of(report(
                new TenantId("tenant-b"),
                requestId,
                List.of(SPACE_A)
        )));

        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> service(reader, accessPolicy).latest(principal(), requestId)
        );

        verifyNoInteractions(accessPolicy);
    }

    @Test
    void rejectsTheWholeReportWhenOneInvolvedSpaceIsNotAllowed() {
        UUID requestId = UUID.randomUUID();
        Set<KnowledgeSpaceId> requested = Set.of(SPACE_A, SPACE_B);
        RetrievalObservationReportReader reader = mock(
                RetrievalObservationReportReader.class
        );
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(reader.findLatestExecution(TENANT, requestId)).thenReturn(Optional.of(report(
                TENANT,
                requestId,
                List.of(SPACE_A, SPACE_B)
        )));
        when(accessPolicy.resolve(principal(), requested)).thenReturn(
                AccessScope.all(TENANT, Set.of(SPACE_A))
        );

        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> service(reader, accessPolicy).latest(principal(), requestId)
        );
    }

    private static RetrievalObservationReportService service(
            RetrievalObservationReportReader reader,
            AccessPolicy accessPolicy
    ) {
        return new RetrievalObservationReportService(reader, accessPolicy);
    }

    private static RetrievalObservationReport report(
            TenantId tenantId,
            UUID requestId,
            List<KnowledgeSpaceId> spaces
    ) {
        List<VisitedRetrievalConfiguration> visits = java.util.stream.IntStream
                .range(0, spaces.size())
                .mapToObj(index -> new VisitedRetrievalConfiguration(
                        index,
                        spaces.get(index),
                        1L,
                        CONFIG
                ))
                .toList();
        return new RetrievalObservationReport(
                tenantId,
                requestId,
                UUID.randomUUID(),
                RetrievalObservationPurpose.ONLINE,
                ObservationCompleteness.INCOMPLETE,
                Set.of(ObservationIncompleteReason.TERMINAL_MISSING),
                List.of(),
                visits,
                List.of(new RetrievalObservationReport.EventFact(
                        UUID.randomUUID(),
                        0L,
                        Set.of(),
                        0,
                        0,
                        RetrievalObservationStage.EXECUTION_STARTED,
                        RetrievalObservationStatus.STARTED,
                        "NONE",
                        RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        NOW,
                        NOW,
                        0L,
                        1,
                        0,
                        RetrievalObservation.CURRENT_SCHEMA_VERSION
                )),
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
