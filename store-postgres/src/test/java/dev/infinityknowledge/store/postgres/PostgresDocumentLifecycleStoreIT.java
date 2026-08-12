package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.management.DocumentLifecycleConflictException;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies lifecycle fencing and projection requeue against PostgreSQL. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresDocumentLifecycleStoreIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static JdbcTemplate jdbc;
    private static PostgresDocumentLifecycleStore store;
    private static PostgresActiveRevisionGuard guard;

    @BeforeAll
    static void migrate() {
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
        store = new PostgresDocumentLifecycleStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        guard = new PostgresActiveRevisionGuard(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
                        dataSource
                )
        );
    }

    @Test
    void archivesRestoresDeletesAndRejectsStaleVersions() {
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        seed(documentId, revisionId);

        var archived = store.transition(
                TENANT, documentId.value(), 0, DocumentStatus.ARCHIVED, NOW
        ).orElseThrow();
        assertTrue(archived.changed());
        assertEquals(1, archived.version());
        assertFalse(guard.isActive(TENANT, documentId, revisionId));
        assertThrows(DocumentLifecycleConflictException.class, () -> store.transition(
                TENANT, documentId.value(), 0, DocumentStatus.ACTIVE, NOW
        ));

        var restored = store.transition(
                TENANT, documentId.value(), 1, DocumentStatus.ACTIVE, NOW.plusSeconds(1)
        ).orElseThrow();
        assertEquals(2, restored.version());
        assertTrue(guard.isActive(TENANT, documentId, revisionId));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT status FROM projection_job
                 WHERE tenant_id = ? AND revision_id = ? AND projection_type = 'VECTOR'
                """, String.class, TENANT.value(), revisionId));

        var deleted = store.transition(
                TENANT, documentId.value(), 2, DocumentStatus.DELETED, NOW.plusSeconds(2)
        ).orElseThrow();
        assertEquals(3, deleted.version());
        assertFalse(guard.isActive(TENANT, documentId, revisionId));

        var recovered = store.transition(
                TENANT, documentId.value(), 3, DocumentStatus.ACTIVE, NOW.plusSeconds(3)
        ).orElseThrow();
        assertEquals(4, recovered.version());
        var noOp = store.transition(
                TENANT, documentId.value(), 4, DocumentStatus.ACTIVE, NOW.plusSeconds(4)
        ).orElseThrow();
        assertFalse(noOp.changed());
        assertEquals(4, noOp.version());
        assertTrue(store.transition(
                new TenantId("tenant-b"), documentId.value(), 4,
                DocumentStatus.ARCHIVED, NOW
        ).isEmpty());
    }

    private static void seed(DocumentId documentId, UUID revisionId) {
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Tenant A', 'ACTIVE', ?, ?)
                """, TENANT.value(), databaseTime(), databaseTime());
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, status, created_at, updated_at)
                VALUES (?, 'engineering', 'Engineering', 'ACTIVE', ?, ?)
                """, TENANT.value(), databaseTime(), databaseTime());
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     status, created_at, updated_at)
                VALUES (?, 'api', 'engineering', 'API', 'API', 'ACTIVE', ?, ?)
                """, TENANT.value(), databaseTime(), databaseTime());
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id, source_type,
                     source_uri, title, status, authority, version, created_at, updated_at)
                VALUES (?, ?, 'engineering', 'api', 'lifecycle', 'API', 'urn:test',
                        'Lifecycle', 'ACTIVE', 100, 0, ?, ?)
                """, TENANT.value(), documentId.value(), databaseTime(), databaseTime());
        jdbc.update("""
                INSERT INTO document_revision
                    (tenant_id, id, document_id, revision_number, content_hash,
                     media_type, language, parser_version, created_at)
                VALUES (?, ?, ?, 1, 'lifecycle-hash', 'text/markdown', 'en', 'test', ?)
                """, TENANT.value(), revisionId, documentId.value(), databaseTime());
        jdbc.update("""
                UPDATE knowledge_document SET active_revision_id = ?
                 WHERE tenant_id = ? AND id = ?
                """, revisionId, TENANT.value(), documentId.value());
        jdbc.update("""
                INSERT INTO projection_job
                    (id, tenant_id, space_id, document_id, revision_id,
                     projection_type, status, attempt_count, available_at,
                     created_at, updated_at, completed_at)
                VALUES (?, ?, 'engineering', ?, ?, 'VECTOR', 'SUCCEEDED', 1, ?, ?, ?, ?)
                """, UUID.randomUUID(), TENANT.value(), documentId.value(), revisionId,
                databaseTime(), databaseTime(), databaseTime(), databaseTime());
    }

    private static java.time.OffsetDateTime databaseTime() {
        return NOW.atOffset(java.time.ZoneOffset.UTC);
    }
}
