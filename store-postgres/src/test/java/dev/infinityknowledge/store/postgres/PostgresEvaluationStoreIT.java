package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.evaluation.EvaluationStore;
import dev.infinityknowledge.evaluation.RetrievalCaseResult;
import dev.infinityknowledge.evaluation.RetrievalEvaluationReport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies evaluation persistence, JSON mapping, transactions and tenant isolation. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresEvaluationStoreIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static PostgresEvaluationStore store;

    @BeforeAll
    static void migrateAndSeed() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        store = new PostgresEvaluationStore(jdbc, transaction);
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        for (String tenant : List.of("tenant-a", "tenant-b")) {
            jdbc.update("""
                    INSERT INTO knowledge_tenant
                        (id, display_name, status, created_at, updated_at)
                    VALUES (?, ?, 'ACTIVE', ?, ?)
                    """, tenant, tenant, now, now);
        }
    }

    @Test
    void persistsCompleteEvaluationLifecycleAndKeepsTenantBoundary() {
        TenantId tenant = new TenantId("tenant-a");
        Instant startedAt = Instant.parse("2026-08-03T01:00:00Z");
        UUID datasetId = UUID.randomUUID();
        var dataset = store.createDataset(
                tenant, datasetId, "retrieval-regression", "baseline", startedAt
        );
        assertEquals(1L, dataset.version());

        UUID caseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        var evaluationCase = new EvaluationStore.Case(
                caseId,
                datasetId,
                "How is Redis diagnosed?",
                Set.of("engineering"),
                Set.of(documentId),
                Set.of(chunkId),
                8,
                Map.of("category", "incident"),
                startedAt
        );
        store.createCase(tenant, evaluationCase);

        UUID runId = UUID.randomUUID();
        store.createRun(tenant, new EvaluationStore.Run(
                runId,
                datasetId,
                "PENDING",
                1,
                0,
                Map.of("topK", 8, "runtime", "knowledge-gateway"),
                Map.of(),
                "admin-1",
                null,
                startedAt,
                null,
                List.of()
        ), principal(tenant), List.of(caseId));
        var lease = store.claim(
                tenant, runId, "worker-a", startedAt.plusSeconds(30), startedAt
        ).orElseThrow();
        assertEquals("admin-1", lease.principal().principalId().value());
        assertEquals(List.of(caseId), lease.caseIds());
        UUID traceId = UUID.randomUUID();
        var report = new RetrievalEvaluationReport(
                1,
                0,
                8,
                1.0D,
                1.0D,
                1.0D,
                1.0D,
                List.of(new RetrievalCaseResult(
                        caseId, traceId, true, true,
                        1.0D, 1.0D, 1.0D, 3, 25L, null
                ))
        );
        Instant completedAt = startedAt.plusSeconds(5L);
        assertTrue(store.completeRun(lease, report, completedAt));

        var storedCase = store.evaluationCase(tenant, caseId).orElseThrow();
        assertEquals(evaluationCase, storedCase);
        var completed = store.run(tenant, runId, true).orElseThrow();
        assertEquals("SUCCEEDED", completed.status());
        assertEquals(completedAt, completed.completedAt());
        assertEquals(1, completed.results().size());
        assertEquals(traceId, completed.results().getFirst().traceId());
        assertEquals(1.0D, completed.metrics().get("hitRate"));

        var summary = store.dataset(tenant, datasetId).orElseThrow();
        assertEquals(1L, summary.caseCount());
        assertEquals(1L, summary.runCount());
        assertEquals(1, store.cases(tenant, datasetId).size());
        assertEquals(1, store.runs(tenant, datasetId).size());
        assertTrue(store.dataset(new TenantId("tenant-b"), datasetId).isEmpty());
        assertTrue(store.run(new TenantId("tenant-b"), runId, true).isEmpty());

        assertFalse(store.failRun(
                lease,
                "LATE_FAILURE",
                completedAt.plusSeconds(1L)
        ));
        assertEquals(
                "SUCCEEDED",
                store.run(tenant, runId, false).orElseThrow().status()
        );
    }

    @Test
    void marksOnlyTheRequestedTenantRunAsFailed() {
        TenantId tenant = new TenantId("tenant-a");
        Instant now = Instant.parse("2026-08-03T02:00:00Z");
        UUID datasetId = UUID.randomUUID();
        store.createDataset(tenant, datasetId, "failure-path", "", now);
        UUID caseId = UUID.randomUUID();
        store.createCase(tenant, new EvaluationStore.Case(
                caseId, datasetId, "failure", Set.of(), Set.of(UUID.randomUUID()),
                Set.of(), 8, Map.of(), now
        ));
        UUID runId = UUID.randomUUID();
        store.createRun(tenant, new EvaluationStore.Run(
                runId, datasetId, "PENDING", 1, 0,
                Map.of(), Map.of(), "admin-1", null, now, null, List.of()
        ), principal(tenant), List.of(caseId));
        var lease = store.claim(
                tenant, runId, "worker-a", now.plusSeconds(30), now
        ).orElseThrow();

        assertTrue(store.failRun(
                lease, "EVALUATION_RUN_FAILED", now.plusSeconds(1L)
        ));

        var failed = store.run(tenant, runId, false).orElseThrow();
        assertEquals("FAILED", failed.status());
        assertEquals("EVALUATION_RUN_FAILED", failed.errorCode());
        assertFalse(failed.results().iterator().hasNext());
    }

    @Test
    void reclaimsExpiredEvaluationAndFencesOldWorker() {
        TenantId tenant = new TenantId("tenant-a");
        Instant now = Instant.parse("2026-08-03T03:00:00Z");
        UUID datasetId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        store.createDataset(tenant, datasetId, "lease-recovery", "", now);
        store.createCase(tenant, new EvaluationStore.Case(
                caseId, datasetId, "lease", Set.of(), Set.of(UUID.randomUUID()),
                Set.of(), 8, Map.of(), now
        ));
        UUID runId = UUID.randomUUID();
        store.createRun(tenant, new EvaluationStore.Run(
                runId, datasetId, "PENDING", 1, 0, Map.of("topK", 8),
                Map.of(), "admin-1", null, now, null, List.of()
        ), principal(tenant), List.of(caseId));

        var stale = store.claim(
                tenant, runId, "worker-a", now.plusSeconds(5), now
        ).orElseThrow();
        var current = store.claim(
                tenant, runId, "worker-b", now.plusSeconds(40), now.plusSeconds(6)
        ).orElseThrow();

        assertTrue(current.leaseToken() > stale.leaseToken());
        assertFalse(store.heartbeat(stale, now.plusSeconds(50), now.plusSeconds(7)));
        assertTrue(store.heartbeat(current, now.plusSeconds(50), now.plusSeconds(7)));
    }

    private static PrincipalContext principal(TenantId tenantId) {
        return new PrincipalContext(
                tenantId,
                new PrincipalId("admin-1"),
                Set.of("knowledge-admin"),
                Set.of("engineering"),
                false
        );
    }
}
