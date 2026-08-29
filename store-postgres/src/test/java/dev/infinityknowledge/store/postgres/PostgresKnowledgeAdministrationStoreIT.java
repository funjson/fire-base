package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.management.KnowledgeAdministrationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * 使用真实 PostgreSQL 验证管理查询的可选过滤和租户边界。
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresKnowledgeAdministrationStoreIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static JdbcTemplate jdbc;
    private static PostgresKnowledgeAdministrationStore store;

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
        store = new PostgresKnowledgeAdministrationStore(jdbc);
        seed();
    }

    @Test
    void listsDocumentsWithoutOptionalFilters() {
        var page = documents(null, null);

        assertEquals(3, page.total());
        assertEquals(3, page.items().size());
    }

    @Test
    void listsDocumentsBySpaceOnly() {
        var page = documents("engineering", null);

        assertEquals(2, page.total());
        assertEquals(2, page.items().size());
    }

    @Test
    void listsDocumentsByStatusOnly() {
        var page = documents(null, "ACTIVE");

        assertEquals(2, page.total());
        assertEquals(2, page.items().size());
    }

    @Test
    void listsDeletedDocumentsOnlyWhenExplicitlyRequested() {
        var page = documents(null, "DELETED");

        assertEquals(1, page.total());
        assertEquals("Deleted", page.items().getFirst().title());
    }

    @Test
    void listsDocumentsBySpaceAndStatus() {
        var page = documents("engineering", "ACTIVE");

        assertEquals(1, page.total());
        assertEquals("Engineering Active", page.items().getFirst().title());
    }

    @Test
    void treatsBlankFiltersAsAbsentAndBoundsPagination() {
        var page = store.documents(
                new TenantId("tenant-a"),
                new KnowledgeAdministrationStore.DocumentFilter(
                        " ",
                        "\t",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        -1
                )
        );

        assertEquals(3, page.total());
        assertEquals(1, page.limit());
        assertEquals(0, page.offset());
        assertEquals(1, page.items().size());
    }

    @Test
    void countsEveryNonTerminalProjectionStateInOverview() {
        var overview = store.overview(new TenantId("tenant-a"));

        assertEquals(3, overview.pendingProjections());
        assertEquals(1, overview.deadProjections());
    }

    private static KnowledgeAdministrationStore.DocumentPage documents(
            String spaceId,
            String status
    ) {
        return store.documents(
                new TenantId("tenant-a"),
                new KnowledgeAdministrationStore.DocumentFilter(
                        spaceId,
                        status,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        100,
                        0
                )
        );
    }

    private static void seed() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        for (String tenant : new String[]{"tenant-a", "tenant-b"}) {
            jdbc.update("""
                    INSERT INTO knowledge_tenant
                        (id, display_name, status, created_at, updated_at)
                    VALUES (?, ?, 'ACTIVE', ?, ?)
                    """, tenant, tenant, now, now);
            for (String space : new String[]{"engineering", "sales"}) {
                jdbc.update("""
                        INSERT INTO knowledge_space
                            (tenant_id, id, name, description, status,
                             version, created_at, updated_at)
                        VALUES (?, ?, ?, '', 'ACTIVE', 0, ?, ?)
                        """, tenant, space, space, now, now);
                jdbc.update("""
                        INSERT INTO connector_instance
                            (tenant_id, id, space_id, connector_type, display_name,
                             config_json, status, version, created_at, updated_at)
                        VALUES (?, ?, ?, 'API', 'API Upload',
                                '{}'::jsonb, 'ACTIVE', 0, ?, ?)
                        """, tenant, "api-upload-" + space, space, now, now);
            }
        }
        UUID projectionDocument = insertDocument(
                "tenant-a", "engineering", "api-upload-engineering",
                "Engineering Active", "ACTIVE", now
        );
        insertProjectionJobs(
                "tenant-a",
                "engineering",
                projectionDocument,
                now
        );
        insertDocument(
                "tenant-a", "engineering", "api-upload-engineering",
                "Engineering Archived", "ARCHIVED", now
        );
        insertDocument(
                "tenant-a", "sales", "api-upload-sales",
                "Sales Active", "ACTIVE", now
        );
        insertDocument(
                "tenant-a", "sales", "api-upload-sales",
                "Deleted", "DELETED", now
        );
        insertDocument(
                "tenant-b", "engineering", "api-upload-engineering",
                "Other Tenant", "ACTIVE", now
        );
    }

    private static UUID insertDocument(
            String tenant,
            String space,
            String connector,
            String title,
            String status,
            OffsetDateTime now
    ) {
        UUID documentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id,
                     source_type, source_uri, title, status, authority,
                     metadata_json, active_revision_id, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'API', ?, ?, ?, 80,
                        '{}'::jsonb, NULL, 0, ?, ?)
                """,
                tenant,
                documentId,
                space,
                connector,
                title.replace(' ', '-').toLowerCase() + ".md",
                "https://example.invalid/" + UUID.randomUUID(),
                title,
                status,
                now,
                now
        );
        return documentId;
    }

    private static void insertProjectionJobs(
            String tenant,
            String space,
            UUID documentId,
            OffsetDateTime now
    ) {
        String[] statuses = {"PENDING", "RETRY", "RUNNING", "DEAD"};
        for (int index = 0; index < statuses.length; index++) {
            String status = statuses[index];
            UUID revisionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO document_revision
                        (tenant_id, id, document_id, revision_number, content_hash,
                         media_type, language, parser_version, created_at)
                    VALUES (?, ?, ?, ?, ?, 'text/markdown', 'zh-CN', 'test', ?)
                    """,
                    tenant,
                    revisionId,
                    documentId,
                    index + 1L,
                    Integer.toHexString(index).repeat(64),
                    now
            );
            boolean running = "RUNNING".equals(status);
            jdbc.update("""
                    INSERT INTO projection_job
                        (id, tenant_id, space_id, document_id, revision_id,
                         projection_type, status, available_at, lease_owner,
                         lease_until, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'VECTOR', ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    tenant,
                    space,
                    documentId,
                    revisionId,
                    status,
                    now,
                    running ? "test-worker" : null,
                    running ? now.plusMinutes(1) : null,
                    now,
                    now
            );
        }
    }
}
