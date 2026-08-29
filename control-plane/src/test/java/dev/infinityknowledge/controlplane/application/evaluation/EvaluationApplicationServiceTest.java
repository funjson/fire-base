package dev.infinityknowledge.controlplane.application.evaluation;

import dev.infinityknowledge.controlplane.api.evaluation.EvaluationApi;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.evaluation.EvaluationStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies evaluation authorization, mapping and orchestration without a database. */
class EvaluationApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T04:00:00Z");

    @Test
    void createsDatasetThroughStoreWithNormalizedInput() {
        EvaluationStore store = mock(EvaluationStore.class);
        var expected = new EvaluationStore.Dataset(
                UUID.randomUUID(), "baseline", "nightly", 1L,
                "DRAFT", 0L, 0L, NOW
        );
        when(store.createDataset(
                eq(new TenantId("tenant-a")),
                any(UUID.class),
                eq("baseline"),
                eq("nightly"),
                eq(NOW)
        )).thenReturn(expected);
        var service = service(store, mock(EvaluationRunCoordinator.class));

        var value = service.createDataset(
                admin(),
                new EvaluationApi.CreateDatasetRequest(" baseline ", " nightly ")
        );

        assertEquals(expected.id(), value.id());
        assertEquals("baseline", value.name());
        assertEquals(1L, value.version());
    }

    @Test
    void executesRunAndPersistsReportThroughStore() {
        EvaluationStore store = mock(EvaluationStore.class);
        EvaluationRunCoordinator coordinator = mock(EvaluationRunCoordinator.class);
        UUID datasetId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        var dataset = new EvaluationStore.Dataset(
                datasetId, "baseline", "", 1L, "DRAFT", 1L, 0L, NOW
        );
        var evaluationCase = new EvaluationStore.Case(
                caseId, datasetId, "Redis timeout", Set.of("engineering"),
                Set.of(documentId), Set.of(), 8, Map.of(), NOW
        );
        when(store.dataset(new TenantId("tenant-a"), datasetId))
                .thenReturn(Optional.of(dataset));
        when(store.cases(new TenantId("tenant-a"), datasetId))
                .thenReturn(List.of(evaluationCase));
        when(store.createRun(
                eq(new TenantId("tenant-a")),
                any(EvaluationStore.Run.class),
                eq(admin()),
                any()
        ))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(store.run(eq(new TenantId("tenant-a")), any(UUID.class), eq(false)))
                .thenAnswer(invocation -> Optional.of(new EvaluationStore.Run(
                        invocation.getArgument(1), datasetId, "RUNNING", 1, 0,
                        Map.of("topK", 8), Map.of(), "admin-1", null,
                        NOW, null, List.of()
                )));
        when(coordinator.submit(eq(new TenantId("tenant-a")), any())).thenReturn(true);
        var service = service(store, coordinator);

        var run = service.start(
                admin(), datasetId, new EvaluationApi.StartRunRequest(8, Map.of())
        );

        assertEquals("RUNNING", run.status());
        verify(coordinator).submit(eq(new TenantId("tenant-a")), any(UUID.class));
    }

    @Test
    void rejectsNonAdminBeforeUsingStore() {
        EvaluationStore store = mock(EvaluationStore.class);
        var service = service(store, mock(EvaluationRunCoordinator.class));

        assertThrows(AccessDeniedException.class, () -> service.datasets(reader()));

        verify(store, never()).datasets(any());
    }

    @Test
    void comparesCompletedRunsAndReportsThresholdAndRegressionViolations() {
        EvaluationStore store = mock(EvaluationStore.class);
        UUID datasetId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        when(store.dataset(new TenantId("tenant-a"), datasetId)).thenReturn(Optional.of(
                new EvaluationStore.Dataset(
                        datasetId, "baseline", "", 1L, "DRAFT", 1, 2, NOW
                )
        ));
        when(store.run(new TenantId("tenant-a"), baselineId, false))
                .thenReturn(Optional.of(completedRun(
                        baselineId, datasetId, 0.9D, 0.8D, 0.7D, 0.6D
                )));
        when(store.run(new TenantId("tenant-a"), candidateId, false))
                .thenReturn(Optional.of(completedRun(
                        candidateId, datasetId, 0.91D, 0.75D, 0.69D, 0.62D
                )));
        var service = service(store, mock(EvaluationRunCoordinator.class));

        var comparison = service.compare(
                admin(),
                datasetId,
                new EvaluationApi.CompareRunsRequest(
                        baselineId, candidateId, 0.9D, 0.76D,
                        0.65D, 0.6D, 0.02D
                )
        );

        assertFalse(comparison.passed());
        assertEquals(-0.05D, comparison.deltas().get("recallAtK"), 0.000_001D);
        assertEquals(2, comparison.violations().size());
        assertEquals(
                Set.of("MINIMUM", "MAXIMUM_REGRESSION"),
                comparison.violations().stream()
                        .map(EvaluationApi.GateViolation::rule)
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    private static EvaluationStore.Run completedRun(
            UUID id,
            UUID datasetId,
            double hitRate,
            double recall,
            double mrr,
            double ndcg
    ) {
        return new EvaluationStore.Run(
                id,
                datasetId,
                "SUCCEEDED",
                1,
                0,
                Map.of("topK", 8),
                Map.of(
                        "hitRate", hitRate,
                        "recallAtK", recall,
                        "mrr", mrr,
                        "ndcgAtK", ndcg
                ),
                "admin-1",
                null,
                NOW,
                NOW,
                List.of()
        );
    }

    private static EvaluationApplicationService service(
            EvaluationStore store,
            EvaluationRunCoordinator coordinator
    ) {
        return new EvaluationApplicationService(
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                coordinator
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
                new PrincipalId("admin-1"),
                roles,
                Set.of("engineering"),
                false
        );
    }
}
