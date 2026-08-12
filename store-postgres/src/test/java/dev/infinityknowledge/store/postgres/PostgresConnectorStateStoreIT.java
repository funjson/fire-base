package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

        var lease = store.claim(
                TENANT, firstRun, "worker-a", NOW.plusSeconds(30), NOW
        ).orElseThrow();
        assertEquals("admin", lease.principal().principalId().value());
        assertTrue(store.complete(lease, 4, 2, NOW.plusSeconds(10)));

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
                runId,
                new PrincipalContext(
                        TENANT,
                        new PrincipalId("admin"),
                        Set.of("knowledge-admin"),
                        Set.of("engineering"),
                        false
                ),
                connectorId,
                snapshotId,
                NOW
        )));

        var lease = store.claim(
                TENANT, runId, "worker-a", NOW.plusSeconds(30), NOW
        ).orElseThrow();
        assertTrue(store.saveCheckpoint(lease, new ConnectorStateStore.ConnectorCheckpoint(
                TENANT,
                connectorId,
                new ConnectorCursor(Map.of("offset", "25")),
                snapshotId,
                NOW.plusSeconds(5)
        ), 25, 2, NOW.plusSeconds(5)));
        assertTrue(store.fail(lease, "CONNECTOR_SYNC_FAILED", NOW.plusSeconds(10)));

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

    @Test
    void reclaimsExpiredLeaseAndRejectsStaleWorkerTransitions() {
        String connectorId = "lease-fencing";
        assertTrue(store.configure(registration(connectorId)));
        UUID runId = UUID.randomUUID();
        assertTrue(store.tryStart(run(connectorId, runId)));

        var stale = store.claim(
                TENANT, runId, "worker-a", NOW.plusSeconds(5), NOW
        ).orElseThrow();
        var current = store.claim(
                TENANT, runId, "worker-b", NOW.plusSeconds(40), NOW.plusSeconds(6)
        ).orElseThrow();

        assertTrue(current.leaseToken() > stale.leaseToken());
        assertFalse(store.heartbeat(
                stale, NOW.plusSeconds(50), NOW.plusSeconds(7)
        ));
        assertFalse(store.complete(stale, 0, 0, NOW.plusSeconds(7)));
        assertTrue(store.complete(current, 0, 0, NOW.plusSeconds(8)));
    }

    @Test
    void promotesOnlyACompleteSnapshotAndArchivesMissingDocuments() {
        String connectorId = "manifest-reconciliation";
        assertTrue(store.configure(registration(connectorId)));
        TestDocument retained = document(connectorId, "retained.md");
        TestDocument removed = document(connectorId, "removed.md");
        establishManifest(connectorId, List.of(retained, removed));

        UUID runId = UUID.randomUUID();
        assertTrue(store.tryStart(run(connectorId, runId)));
        var lease = store.claim(
                TENANT, runId, "worker-a", NOW.plusSeconds(60), NOW.plusSeconds(20)
        ).orElseThrow();
        assertTrue(store.stageManifest(
                lease,
                new KnowledgeSpaceId("engineering"),
                List.of(retained.manifestEntry()),
                NOW.plusSeconds(21)
        ));
        assertTrue(store.saveCheckpoint(
                lease,
                new ConnectorStateStore.ConnectorCheckpoint(
                        TENANT,
                        connectorId,
                        ConnectorCursor.initial(),
                        lease.snapshotId(),
                        NOW.plusSeconds(21)
                ),
                1,
                0,
                NOW.plusSeconds(21)
        ));

        var completion = store.completeFullSnapshot(
                lease,
                new KnowledgeSpaceId("engineering"),
                1,
                0,
                NOW.plusSeconds(22)
        ).orElseThrow();

        assertEquals(1, completion.recordsDeleted());
        assertEquals("ACTIVE", documentStatus(retained.documentId()));
        assertEquals("ARCHIVED", documentStatus(removed.documentId()));
        var activeGuard = new PostgresActiveRevisionGuard(
                new NamedParameterJdbcTemplate(jdbc.getDataSource())
        );
        assertTrue(activeGuard.isActive(
                TENANT,
                new DocumentId(retained.documentId()),
                retained.revisionId()
        ));
        assertFalse(activeGuard.isActive(
                TENANT,
                new DocumentId(removed.documentId()),
                removed.revisionId()
        ));
        assertEquals(1L, manifestCount(connectorId));
        var status = store.findRun(TENANT, runId).orElseThrow();
        assertEquals(1, status.recordsDeleted());
    }

    @Test
    void failedPartialSnapshotNeverChangesManifestOrDocumentLifecycle() {
        String connectorId = "manifest-failure";
        assertTrue(store.configure(registration(connectorId)));
        TestDocument retained = document(connectorId, "retained.md");
        TestDocument wouldBeRemoved = document(connectorId, "would-be-removed.md");
        establishManifest(connectorId, List.of(retained, wouldBeRemoved));

        UUID runId = UUID.randomUUID();
        assertTrue(store.tryStart(run(connectorId, runId)));
        var lease = store.claim(
                TENANT, runId, "worker-a", NOW.plusSeconds(60), NOW.plusSeconds(20)
        ).orElseThrow();
        assertTrue(store.stageManifest(
                lease,
                new KnowledgeSpaceId("engineering"),
                List.of(retained.manifestEntry()),
                NOW.plusSeconds(21)
        ));

        assertTrue(store.fail(lease, "CONNECTOR_SYNC_FAILED", NOW.plusSeconds(22)));

        assertEquals("ACTIVE", documentStatus(wouldBeRemoved.documentId()));
        assertEquals(2L, manifestCount(connectorId));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM connector_snapshot_manifest WHERE run_id = ?
                """, Long.class, runId));
    }

    @Test
    void recoveredSnapshotRestartIsFencedAndCannotClearCurrentWorkerData() {
        String connectorId = "snapshot-restart-fencing";
        assertTrue(store.configure(registration(connectorId)));
        TestDocument observed = document(connectorId, "observed.md");
        UUID runId = UUID.randomUUID();
        assertTrue(store.tryStart(run(connectorId, runId)));

        var stale = store.claim(
                TENANT, runId, "worker-a", NOW.plusSeconds(5), NOW
        ).orElseThrow();
        assertTrue(store.stageManifest(
                stale,
                new KnowledgeSpaceId("engineering"),
                List.of(observed.manifestEntry()),
                NOW.plusSeconds(1)
        ));
        assertTrue(store.saveCheckpoint(
                stale,
                new ConnectorStateStore.ConnectorCheckpoint(
                        TENANT,
                        connectorId,
                        new ConnectorCursor(Map.of("offset", "25")),
                        stale.snapshotId(),
                        NOW.plusSeconds(2)
                ),
                25,
                1,
                NOW.plusSeconds(2)
        ));

        var current = store.claim(
                TENANT, runId, "worker-b", NOW.plusSeconds(60), NOW.plusSeconds(6)
        ).orElseThrow();
        assertEquals(new ConnectorCursor(Map.of("offset", "25")), current.cursor());
        assertEquals(25, current.recordsSeen());
        assertFalse(store.restartFullSnapshot(stale, NOW.plusSeconds(7)));
        assertEquals(1L, snapshotManifestCount(runId));

        assertTrue(store.restartFullSnapshot(current, NOW.plusSeconds(7)));
        assertEquals(0L, snapshotManifestCount(runId));
        assertEquals("{}", jdbc.queryForObject("""
                SELECT cursor_json::text FROM connector_checkpoint
                 WHERE tenant_id = ? AND connector_id = ?
                """, String.class, TENANT.value(), connectorId));
        Map<String, Object> counters = jdbc.queryForMap("""
                SELECT records_seen, records_changed FROM connector_sync_run
                 WHERE tenant_id = ? AND id = ?
                """, TENANT.value(), runId);
        assertEquals(0L, ((Number) counters.get("records_seen")).longValue());
        assertEquals(0L, ((Number) counters.get("records_changed")).longValue());

        assertTrue(store.stageManifest(
                current,
                new KnowledgeSpaceId("engineering"),
                List.of(observed.manifestEntry()),
                NOW.plusSeconds(8)
        ));
        assertFalse(store.restartFullSnapshot(current, NOW.plusSeconds(8)));
        assertFalse(store.restartFullSnapshot(stale, NOW.plusSeconds(8)));
        assertEquals(1L, snapshotManifestCount(runId));
    }

    @Test
    void recoveredSnapshotWithInitialCursorClearsAbandonedStagingOnlyOnce() {
        String connectorId = "snapshot-initial-cursor-restart";
        assertTrue(store.configure(registration(connectorId)));
        TestDocument observed = document(connectorId, "observed-before-checkpoint.md");
        UUID runId = UUID.randomUUID();
        assertTrue(store.tryStart(run(connectorId, runId)));

        var stale = store.claim(
                TENANT, runId, "worker-a", NOW.plusSeconds(5), NOW
        ).orElseThrow();
        assertTrue(store.stageManifest(
                stale,
                new KnowledgeSpaceId("engineering"),
                List.of(observed.manifestEntry()),
                NOW.plusSeconds(1)
        ));

        var current = store.claim(
                TENANT, runId, "worker-b", NOW.plusSeconds(60), NOW.plusSeconds(6)
        ).orElseThrow();
        assertEquals(ConnectorCursor.initial(), current.cursor());
        assertTrue(current.leaseToken() > 1);
        assertEquals(1L, snapshotManifestCount(runId));

        assertTrue(store.restartFullSnapshot(current, NOW.plusSeconds(7)));
        assertEquals(0L, snapshotManifestCount(runId));
        assertTrue(store.stageManifest(
                current,
                new KnowledgeSpaceId("engineering"),
                List.of(observed.manifestEntry()),
                NOW.plusSeconds(8)
        ));
        assertFalse(store.restartFullSnapshot(current, NOW.plusSeconds(8)));
        assertFalse(store.restartFullSnapshot(stale, NOW.plusSeconds(8)));
        assertEquals(1L, snapshotManifestCount(runId));
    }

    @Test
    void aNewSnapshotNeverResumesTheCursorOfAFailedSnapshot() {
        String connectorId = "snapshot-cursor";
        assertTrue(store.configure(registration(connectorId)));
        UUID failedRunId = UUID.randomUUID();
        UUID failedSnapshotId = UUID.randomUUID();
        assertTrue(store.tryStart(new ConnectorStateStore.SynchronizationRun(
                failedRunId,
                principal(),
                connectorId,
                failedSnapshotId,
                NOW
        )));
        var failedLease = store.claim(
                TENANT, failedRunId, "worker-a", NOW.plusSeconds(30), NOW
        ).orElseThrow();
        assertTrue(store.saveCheckpoint(
                failedLease,
                new ConnectorStateStore.ConnectorCheckpoint(
                        TENANT,
                        connectorId,
                        new ConnectorCursor(Map.of("offset", "25")),
                        failedSnapshotId,
                        NOW.plusSeconds(2)
                ),
                25,
                1,
                NOW.plusSeconds(2)
        ));
        assertTrue(store.fail(failedLease, "FAILED", NOW.plusSeconds(3)));

        UUID nextRunId = UUID.randomUUID();
        assertTrue(store.tryStart(run(connectorId, nextRunId)));
        var nextLease = store.claim(
                TENANT, nextRunId, "worker-b", NOW.plusSeconds(60), NOW.plusSeconds(4)
        ).orElseThrow();

        assertEquals(ConnectorCursor.initial(), nextLease.cursor());
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
                principal(),
                connectorId,
                UUID.randomUUID(),
                NOW
        );
    }

    private static PrincipalContext principal() {
        return new PrincipalContext(
                TENANT,
                new PrincipalId("admin"),
                Set.of("knowledge-admin"),
                Set.of("engineering"),
                false
        );
    }

    private static TestDocument document(String connectorId, String externalId) {
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id,
                     source_type, source_uri, title, status, authority,
                     metadata_json, active_revision_id, version, created_at, updated_at)
                VALUES (?, ?, 'engineering', ?, ?, 'OBSIDIAN', ?, ?,
                        'ACTIVE', 70, '{}'::jsonb, NULL, 0, ?, ?)
                """,
                TENANT.value(),
                documentId,
                connectorId,
                externalId,
                "obsidian://open?vault=Knowledge&file=" + externalId,
                externalId,
                now,
                now
        );
        String contentHash = java.util.HexFormat.of().formatHex(
                externalId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        contentHash = (contentHash + "0".repeat(128)).substring(0, 64);
        jdbc.update("""
                INSERT INTO document_revision
                    (tenant_id, id, document_id, revision_number, content_hash,
                     media_type, language, parser_version, object_uri, created_at)
                VALUES (?, ?, ?, 1, ?, 'text/markdown', 'en', 'test', NULL, ?)
                """, TENANT.value(), revisionId, documentId, contentHash, now);
        jdbc.update("""
                UPDATE knowledge_document SET active_revision_id = ?
                 WHERE tenant_id = ? AND id = ?
                """, revisionId, TENANT.value(), documentId);
        return new TestDocument(documentId, revisionId, externalId, contentHash);
    }

    private static void establishManifest(
            String connectorId,
            List<TestDocument> documents
    ) {
        UUID runId = UUID.randomUUID();
        assertTrue(store.tryStart(run(connectorId, runId)));
        var lease = store.claim(
                TENANT, runId, "baseline-worker", NOW.plusSeconds(15), NOW
        ).orElseThrow();
        assertTrue(store.stageManifest(
                lease,
                new KnowledgeSpaceId("engineering"),
                documents.stream().map(TestDocument::manifestEntry).toList(),
                NOW.plusSeconds(1)
        ));
        assertTrue(store.saveCheckpoint(
                lease,
                new ConnectorStateStore.ConnectorCheckpoint(
                        TENANT,
                        connectorId,
                        ConnectorCursor.initial(),
                        lease.snapshotId(),
                        NOW.plusSeconds(1)
                ),
                documents.size(),
                documents.size(),
                NOW.plusSeconds(1)
        ));
        assertEquals(0, store.completeFullSnapshot(
                lease,
                new KnowledgeSpaceId("engineering"),
                documents.size(),
                documents.size(),
                NOW.plusSeconds(2)
        ).orElseThrow().recordsDeleted());
    }

    private static String documentStatus(UUID documentId) {
        return jdbc.queryForObject("""
                SELECT status FROM knowledge_document WHERE tenant_id = ? AND id = ?
                """, String.class, TENANT.value(), documentId);
    }

    private static long manifestCount(String connectorId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM connector_manifest
                 WHERE tenant_id = ? AND connector_id = ?
                """, Long.class, TENANT.value(), connectorId);
        return count == null ? 0 : count;
    }

    private static long snapshotManifestCount(UUID runId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM connector_snapshot_manifest WHERE run_id = ?
                """, Long.class, runId);
        return count == null ? 0 : count;
    }

    private record TestDocument(
            UUID documentId,
            UUID revisionId,
            String externalId,
            String contentHash
    ) {
        private ConnectorStateStore.ManifestEntry manifestEntry() {
            return new ConnectorStateStore.ManifestEntry(
                    externalId,
                    "obsidian://open?vault=Knowledge&file=" + externalId,
                    contentHash,
                    documentId
            );
        }
    }
}
