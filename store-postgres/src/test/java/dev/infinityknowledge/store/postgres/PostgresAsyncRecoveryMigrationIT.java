package dev.infinityknowledge.store.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies V10 can upgrade a pre-lease database that contains live async runs. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresAsyncRecoveryMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    @Test
    void migratesRunningConnectorAndEvaluationRowsFromV9ToLatest() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OffsetDateTime now = OffsetDateTime.parse("2026-08-11T00:00:00Z");
        UUID connectorRun = UUID.randomUUID();
        UUID evaluationDataset = UUID.randomUUID();
        UUID evaluationCase = UUID.randomUUID();
        UUID laterEvaluationCase = UUID.randomUUID();
        UUID evaluationRun = UUID.randomUUID();
        UUID completedEvaluationRun = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES ('tenant-a', 'Tenant A', 'ACTIVE', ?, ?)
                """, now, now);
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, description, status, version,
                     created_at, updated_at)
                VALUES ('tenant-a', 'engineering', 'Engineering', '', 'ACTIVE', 0, ?, ?)
                """, now, now);
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, version, created_at, updated_at)
                VALUES ('tenant-a', 'notes', 'engineering', 'OBSIDIAN', 'Notes',
                        '{}'::jsonb, 'ACTIVE', 0, ?, ?)
                """, now, now);
        jdbc.update("""
                INSERT INTO connector_sync_run
                    (id, tenant_id, connector_id, snapshot_id, status, started_at)
                VALUES (?, 'tenant-a', 'notes', ?, 'RUNNING', ?)
                """, connectorRun, UUID.randomUUID(), now);
        jdbc.update("""
                INSERT INTO evaluation_dataset
                    (tenant_id, id, name, version, status, description, created_at)
                VALUES ('tenant-a', ?, 'Recovery', 1, 'ACTIVE', '', ?)
                """, evaluationDataset, now);
        jdbc.update("""
                INSERT INTO evaluation_case
                    (tenant_id, id, dataset_id, query_text, created_at)
                VALUES ('tenant-a', ?, ?, 'recovery query', ?)
                """, evaluationCase, evaluationDataset, now);
        jdbc.update("""
                INSERT INTO evaluation_run
                    (tenant_id, id, dataset_id, status, configuration_json,
                     metrics_json, requested_by, case_count, failed_case_count,
                     started_at, completed_at)
                VALUES ('tenant-a', ?, ?, 'SUCCEEDED', '{}'::jsonb, '{}'::jsonb,
                        'admin', 1, 0, ?, ?)
                """, completedEvaluationRun, evaluationDataset, now, now);
        jdbc.update("""
                INSERT INTO evaluation_case_result
                    (tenant_id, run_id, case_id, status, hit, recall_at_k,
                     reciprocal_rank, ndcg_at_k, result_count, duration_ms, created_at)
                VALUES ('tenant-a', ?, ?, 'SUCCEEDED', true, 1, 1, 1, 1, 5, ?)
                """, completedEvaluationRun, evaluationCase, now);
        jdbc.update("""
                INSERT INTO evaluation_case
                    (tenant_id, id, dataset_id, query_text, created_at)
                VALUES ('tenant-a', ?, ?, 'added after completed run', ?)
                """, laterEvaluationCase, evaluationDataset, now.plusSeconds(1));
        jdbc.update("""
                INSERT INTO evaluation_run
                    (tenant_id, id, dataset_id, status, configuration_json,
                     requested_by, started_at)
                VALUES ('tenant-a', ?, ?, 'RUNNING', '{}'::jsonb, 'admin', ?)
                """, evaluationRun, evaluationDataset, now);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM connector_sync_run WHERE id = ?",
                String.class,
                connectorRun
        ));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM evaluation_run WHERE tenant_id = 'tenant-a' AND id = ?",
                String.class,
                evaluationRun
        ));
        assertEquals(1, jdbc.queryForObject(
                "SELECT jsonb_array_length(case_ids_json) FROM evaluation_run "
                        + "WHERE tenant_id = 'tenant-a' AND id = ?",
                Integer.class,
                completedEvaluationRun
        ));
        assertTrue(jdbc.queryForObject("""
                SELECT case_ids_json @> jsonb_build_array(?::uuid)
                  FROM evaluation_run
                 WHERE tenant_id = 'tenant-a' AND id = ?
                """, Boolean.class, evaluationCase, completedEvaluationRun));
        assertEquals(2, jdbc.queryForObject(
                "SELECT jsonb_array_length(case_ids_json) FROM evaluation_run "
                        + "WHERE tenant_id = 'tenant-a' AND id = ?",
                Integer.class,
                evaluationRun
        ));
        assertNull(columnDefault(jdbc, "connector_sync_run", "principal_json"));
        assertNull(columnDefault(jdbc, "evaluation_run", "principal_json"));
        assertNull(columnDefault(jdbc, "evaluation_run", "case_ids_json"));
    }

    private static String columnDefault(
            JdbcTemplate jdbc,
            String tableName,
            String columnName
    ) {
        return jdbc.queryForObject("""
                SELECT column_default
                  FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = ?
                   AND column_name = ?
                """, String.class, tableName, columnName);
    }
}
