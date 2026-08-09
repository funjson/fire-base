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

/**
 * 验证旧版租户级 API Connector 数据可安全迁移到空间级身份。
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresDocumentIdentityMigrationIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    @Test
    void migratesLegacyConnectorAndRepairsSpaceProjection() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("4")
                .load()
                .migrate();
        var jdbc = new JdbcTemplate(dataSource);
        LegacyIdentity legacy = seedLegacyMismatch(jdbc);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertEquals("api-upload:space-a", jdbc.queryForObject("""
                SELECT connector_id FROM knowledge_document
                WHERE tenant_id = 'legacy-tenant' AND id = ?
                """, String.class, legacy.documentId()));
        assertEquals("space-a", jdbc.queryForObject("""
                SELECT space_id FROM knowledge_chunk
                WHERE tenant_id = 'legacy-tenant' AND id = ?
                """, String.class, legacy.chunkId()));
        assertEquals("space-a", jdbc.queryForObject("""
                SELECT space_id FROM projection_job
                WHERE tenant_id = 'legacy-tenant' AND id = ?
                """, String.class, legacy.jobId()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM connector_instance
                WHERE tenant_id = 'legacy-tenant'
                  AND id IN ('api-upload:space-a', 'api-upload:space-b')
                  AND status = 'ACTIVE'
                """, Integer.class));
        assertEquals("DELETED", jdbc.queryForObject("""
                SELECT status FROM connector_instance
                WHERE tenant_id = 'legacy-tenant' AND id = 'api-upload'
                """, String.class));
    }

    private static LegacyIdentity seedLegacyMismatch(JdbcTemplate jdbc) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES ('legacy-tenant', 'Legacy Tenant', 'ACTIVE', ?, ?)
                """, now, now);
        for (String space : new String[]{"space-a", "space-b"}) {
            jdbc.update("""
                    INSERT INTO knowledge_space
                        (tenant_id, id, name, status, created_at, updated_at)
                    VALUES ('legacy-tenant', ?, ?, 'ACTIVE', ?, ?)
                    """, space, space, now, now);
        }
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, created_at, updated_at)
                VALUES ('legacy-tenant', 'api-upload', 'space-b', 'API',
                        'API Upload', '{}'::jsonb, 'ACTIVE', ?, ?)
                """, now, now);
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id, source_type,
                     source_uri, title, status, authority, metadata_json,
                     active_revision_id, version, created_at, updated_at)
                VALUES ('legacy-tenant', ?, 'space-a', 'api-upload', 'shared.md',
                        'API', 'https://example.invalid/shared.md', 'Shared',
                        'ACTIVE', 80, '{}'::jsonb, NULL, 1, ?, ?)
                """, documentId, now, now);
        jdbc.update("""
                INSERT INTO document_revision
                    (tenant_id, id, document_id, revision_number, content_hash,
                     media_type, language, parser_version, object_uri, created_at)
                VALUES ('legacy-tenant', ?, ?, 1, ?, 'text/markdown', 'zh-CN',
                        'markdown-structure-v1', NULL, ?)
                """, revisionId, documentId, "a".repeat(64), now);
        jdbc.update("""
                UPDATE knowledge_document SET active_revision_id = ?
                WHERE tenant_id = 'legacy-tenant' AND id = ?
                """, revisionId, documentId);
        jdbc.update("""
                INSERT INTO knowledge_chunk
                    (tenant_id, id, space_id, document_id, revision_id, ordinal,
                     section_path_json, element_ids_json, content, content_hash,
                     metadata_json, created_at)
                VALUES ('legacy-tenant', ?, 'space-b', ?, ?, 0, '[]'::jsonb,
                        '[]'::jsonb, 'legacy body', ?, '{}'::jsonb, ?)
                """, chunkId, documentId, revisionId, "b".repeat(64), now);
        jdbc.update("""
                INSERT INTO projection_job
                    (id, tenant_id, space_id, document_id, revision_id,
                     projection_type, status, attempt_count, available_at,
                     created_at, updated_at)
                VALUES (?, 'legacy-tenant', 'space-b', ?, ?, 'VECTOR',
                        'PENDING', 0, ?, ?, ?)
                """, jobId, documentId, revisionId, now, now, now);
        return new LegacyIdentity(documentId, chunkId, jobId);
    }

    private record LegacyIdentity(UUID documentId, UUID chunkId, UUID jobId) {
    }
}
