package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.EvaluationApi;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.evaluation.EvaluationStore;
import dev.infinityknowledge.spi.KnowledgeGateway;
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
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        KnowledgeGateway gateway = mock(KnowledgeGateway.class);
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
        var service = service(store, gateway, Runnable::run);

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
        KnowledgeGateway gateway = mock(KnowledgeGateway.class);
        UUID datasetId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
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
        when(store.createRun(eq(new TenantId("tenant-a")), any(EvaluationStore.Run.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(store.run(eq(new TenantId("tenant-a")), any(UUID.class), eq(false)))
                .thenAnswer(invocation -> Optional.of(new EvaluationStore.Run(
                        invocation.getArgument(1), datasetId, "RUNNING", 1, 0,
                        Map.of("topK", 8), Map.of(), "admin-1", null,
                        NOW, null, List.of()
                )));
        when(gateway.retrieve(any(KnowledgeQuery.class))).thenAnswer(invocation -> {
            KnowledgeQuery query = invocation.getArgument(0);
            return new EvidenceBundle(
                    query.requestId(), UUID.randomUUID(), query.principal().tenantId(),
                    List.of(), false, List.of(), NOW
            );
        });
        var service = service(store, gateway, Runnable::run);

        var run = service.start(
                admin(), datasetId, new EvaluationApi.StartRunRequest(8, Map.of())
        );

        assertEquals("RUNNING", run.status());
        verify(store).completeRun(
                eq(new TenantId("tenant-a")),
                any(UUID.class),
                any(),
                eq(NOW)
        );
        verify(store, never()).failRun(any(), any(), any(), any());
    }

    @Test
    void rejectsNonAdminBeforeUsingStore() {
        EvaluationStore store = mock(EvaluationStore.class);
        var service = service(store, mock(KnowledgeGateway.class), Runnable::run);

        assertThrows(AccessDeniedException.class, () -> service.datasets(reader()));

        verify(store, never()).datasets(any());
    }

    private static EvaluationApplicationService service(
            EvaluationStore store,
            KnowledgeGateway gateway,
            Executor executor
    ) {
        return new EvaluationApplicationService(
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                executor,
                new EvaluationRunWorker(
                        store,
                        gateway,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                )
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
