package dev.infinityknowledge.store.postgres;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies V15 diagnoses dirty ownership and then enforces aggregate-bound revisions. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresRevisionOwnershipMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    @Test
    void diagnosesDirtyDocumentHeadAndEnforcesDocumentAndPageOwnership() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("14")
                .load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OwnershipFixture fixture = seedOwnershipFixture(jdbc);
        jdbc.update("""
                UPDATE knowledge_document
                   SET active_revision_id = ?
                 WHERE tenant_id = 'tenant-owner' AND id = ?
                """, fixture.documentRevisionB(), fixture.documentA());

        FlywayException dirty = assertThrows(
                FlywayException.class,
                () -> Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate()
        );
        assertTrue(messages(dirty).contains("cross-document revision ownership"));

        jdbc.update("""
                UPDATE knowledge_document
                   SET active_revision_id = ?
                 WHERE tenant_id = 'tenant-owner' AND id = ?
                """, fixture.documentRevisionA(), fixture.documentA());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE knowledge_document
                   SET active_revision_id = ?
                 WHERE tenant_id = 'tenant-owner' AND id = ?
                """, fixture.documentRevisionB(), fixture.documentA()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE knowledge_page
                   SET latest_revision_id = ?
                 WHERE tenant_id = 'tenant-owner' AND id = ?
                """, fixture.pageRevisionB(), fixture.pageA()));
    }

    private static OwnershipFixture seedOwnershipFixture(JdbcTemplate jdbc) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-11T00:00:00Z");
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID documentRevisionA = UUID.randomUUID();
        UUID documentRevisionB = UUID.randomUUID();
        UUID pageA = UUID.randomUUID();
        UUID pageB = UUID.randomUUID();
        UUID pageRevisionA = UUID.randomUUID();
        UUID pageRevisionB = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES ('tenant-owner', 'Ownership', 'ACTIVE', ?, ?)
                """, now, now);
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, description, status, version,
                     created_at, updated_at)
                VALUES ('tenant-owner', 'space-owner', 'Ownership', '',
                        'ACTIVE', 0, ?, ?)
                """, now, now);
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, version, created_at, updated_at)
                VALUES ('tenant-owner', 'owner-source', 'space-owner', 'API',
                        'Owner source', '{}'::jsonb, 'ACTIVE', 0, ?, ?)
                """, now, now);
        insertDocument(jdbc, documentA, "a.md", now);
        insertDocument(jdbc, documentB, "b.md", now);
        insertDocumentRevision(jdbc, documentRevisionA, documentA, "a", now);
        insertDocumentRevision(jdbc, documentRevisionB, documentB, "b", now);
        jdbc.update("""
                UPDATE knowledge_document
                   SET active_revision_id = CASE id WHEN ? THEN ? ELSE ? END
                 WHERE tenant_id = 'tenant-owner' AND id IN (?, ?)
                """, documentA, documentRevisionA, documentRevisionB,
                documentA, documentB);
        insertPage(jdbc, pageA, "page-a", now);
        insertPage(jdbc, pageB, "page-b", now);
        insertPageRevision(jdbc, pageRevisionA, pageA, "a", now);
        insertPageRevision(jdbc, pageRevisionB, pageB, "b", now);
        jdbc.update("""
                UPDATE knowledge_page
                   SET latest_revision_id = CASE id WHEN ? THEN ? ELSE ? END
                 WHERE tenant_id = 'tenant-owner' AND id IN (?, ?)
                """, pageA, pageRevisionA, pageRevisionB, pageA, pageB);
        return new OwnershipFixture(
                documentA, documentRevisionA, documentRevisionB,
                pageA, pageRevisionB
        );
    }

    private static void insertDocument(
            JdbcTemplate jdbc,
            UUID documentId,
            String externalId,
            OffsetDateTime now
    ) {
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id, source_type,
                     source_uri, title, status, authority, metadata_json,
                     active_revision_id, version, created_at, updated_at)
                VALUES ('tenant-owner', ?, 'space-owner', 'owner-source', ?, 'API',
                        ?, ?, 'ACTIVE', 80, '{}'::jsonb, NULL, 0, ?, ?)
                """, documentId, externalId, "memory://" + externalId,
                externalId, now, now);
    }

    private static void insertDocumentRevision(
            JdbcTemplate jdbc,
            UUID revisionId,
            UUID documentId,
            String content,
            OffsetDateTime now
    ) {
        jdbc.update("""
                INSERT INTO document_revision
                    (tenant_id, id, document_id, revision_number, content_hash,
                     media_type, language, parser_version, object_uri, created_at)
                VALUES ('tenant-owner', ?, ?, 1, ?, 'text/markdown', 'en',
                        'test-v1', NULL, ?)
                """, revisionId, documentId, content.repeat(64), now);
    }

    private static void insertPage(
            JdbcTemplate jdbc,
            UUID pageId,
            String slug,
            OffsetDateTime now
    ) {
        jdbc.update("""
                INSERT INTO knowledge_page
                    (tenant_id, id, space_id, slug, title, status,
                     latest_revision_id, active_revision_id, version,
                     created_at, updated_at)
                VALUES ('tenant-owner', ?, 'space-owner', ?, ?, 'DRAFT',
                        NULL, NULL, 0, ?, ?)
                """, pageId, slug, slug, now, now);
    }

    private static void insertPageRevision(
            JdbcTemplate jdbc,
            UUID revisionId,
            UUID pageId,
            String content,
            OffsetDateTime now
    ) {
        jdbc.update("""
                INSERT INTO knowledge_page_revision
                    (tenant_id, id, page_id, revision_number, summary, markdown,
                     sources_json, content_hash, compiler_version, generated_by,
                     created_by, created_at)
                VALUES ('tenant-owner', ?, ?, 1, ?, ?, '[{"source":"test"}]'::jsonb,
                        ?, 'test-v1', 'test', 'test', ?)
                """, revisionId, pageId, content, content,
                content.repeat(64), now);
    }

    private static String messages(Throwable failure) {
        StringBuilder values = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            values.append(current.getMessage()).append('\n');
        }
        return values.toString();
    }

    private record OwnershipFixture(
            UUID documentA,
            UUID documentRevisionA,
            UUID documentRevisionB,
            UUID pageA,
            UUID pageRevisionB
    ) {
    }
}
