package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies connector state, tenant boundaries and single-flight execution
 * against a real PostgreSQL instance.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresConnectorStateStoreIT {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static JdbcTemplate jdbc;
    private static PostgresConnectorStateStore store;

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
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresConnectorStateStore(jdbc);
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES ('tenant-a', 'Tenant A', 'ACTIVE', ?, ?),
                       ('tenant-b', 'Tenant B', 'ACTIVE', ?, ?)
                """, now, now, now, now);
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, description, status,
                     version, created_at, updated_at)
                VALUES ('tenant-a', 'engineering', 'Engineering', '', 'ACTIVE', 0, ?, ?),
                       ('tenant-a', 'archived', 'Archived', '', 'ARCHIVED', 0, ?, ?),
                       ('tenant-b', 'engineering', 'Engineering', '', 'ACTIVE', 0, ?, ?)
                """, now, now, now, now, now, now);
    }

    @Test
    void configuresAndLoadsActiveConnectorWithinTenant() {
        assertTrue(store.configure(registration("notes")));

        var definition = store.findActive(TENANT, "notes").orElseThrow();

        assertEquals("engineering", definition.spaceId().value());
        assertEquals("OBSIDIAN", definition.type());
        assertEquals(75, definition.authority());
        assertEquals("Knowledge", definition.configuration().get("vaultName"));
        assertFalse(store.findActive(new TenantId("tenant-b"), "notes").isPresent());
    }

    @Test
    void refusesConfigurationWhenSpaceIsNotActive() {
        var archived = new ConnectorStateStore.ConnectorRegistration(
                TENANT,
                "archived-notes",
                new KnowledgeSpaceId("archived"),
                "OBSIDIAN",
                "Archived notes",
                70,
                Map.of("vaultName", "Archived", "vaultPath", "C:\\vault"),
                NOW
        );

        assertFalse(store.configure(archived));
        assertFalse(store.findActive(TENANT, "archived-notes").isPresent());
    }

    @Test
    void enforcesSingleFlightAndAllowsNextRunAfterCompletion() {
        String connectorId = "single-flight";
        assertTrue(store.configure(registration(connectorId)));
        UUID firstRun = UUID.randomUUID();
        assertTrue(store.tryStart(run(connectorId, firstRun)));
        assertFalse(store.tryStart(run(connectorId, UUID.randomUUID())));

        store.complete(TENANT, firstRun, 4, 2, NOW.plusSeconds(10));

        var completed = store.findRun(TENANT, firstRun).orElseThrow();
        assertEquals("SUCCEEDED", completed.status());
        assertEquals(4, completed.recordsSeen());
        assertEquals(2, completed.recordsChanged());
        assertFalse(store.findRun(new TenantId("tenant-b"), firstRun).isPresent());
        assertTrue(store.tryStart(run(connectorId, UUID.randomUUID())));
    }

    @Test
    void persistsCheckpointAndFailedRunState() {
        String connectorId = "checkpoint";
        assertTrue(store.configure(registration(connectorId)));
        UUID runId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        assertTrue(store.tryStart(new ConnectorStateStore.SynchronizationRun(
                runId, TENANT, connectorId, snapshotId, NOW
        )));

        store.saveCheckpoint(new ConnectorStateStore.ConnectorCheckpoint(
                TENANT,
                connectorId,
                new ConnectorCursor(Map.of("offset", "25")),
                snapshotId,
                NOW.plusSeconds(5)
        ));
        store.fail(TENANT, runId, "CONNECTOR_SYNC_FAILED", NOW.plusSeconds(10));

        var failed = store.findRun(TENANT, runId).orElseThrow();
        assertEquals("FAILED", failed.status());
        assertEquals("CONNECTOR_SYNC_FAILED", failed.errorCode());
        assertEquals(
                "25",
                jdbc.queryForObject("""
                        SELECT cursor_json ->> 'offset'
                          FROM connector_checkpoint
                         WHERE tenant_id = ? AND connector_id = ?
                        """, String.class, TENANT.value(), connectorId)
        );
        assertEquals(
                "FAILED",
                jdbc.queryForObject("""
                        SELECT status
                          FROM connector_sync_run
                         WHERE tenant_id = ? AND id = ?
                        """, String.class, TENANT.value(), runId)
        );
    }

    private static ConnectorStateStore.ConnectorRegistration registration(
            String connectorId
    ) {
        return new ConnectorStateStore.ConnectorRegistration(
                TENANT,
                connectorId,
                new KnowledgeSpaceId("engineering"),
                "OBSIDIAN",
                "Knowledge notes",
                75,
                Map.of("vaultName", "Knowledge", "vaultPath", "C:\\vault"),
                NOW
        );
    }

    private static ConnectorStateStore.SynchronizationRun run(
            String connectorId,
            UUID runId
    ) {
        return new ConnectorStateStore.SynchronizationRun(
                runId,
                TENANT,
                connectorId,
                UUID.randomUUID(),
                NOW
        );
    }
}
