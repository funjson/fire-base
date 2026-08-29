package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the active-revision publication guard against the real PostgreSQL schema.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresActiveRevisionGuardIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static JdbcTemplate jdbc;
    private static PostgresActiveRevisionGuard guard;
    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        guard = new PostgresActiveRevisionGuard(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void filtersOneCandidateBatchAgainstTheActiveDocumentHeads() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentId documentId = DocumentId.random();
        UUID staleRevision = UUID.randomUUID();
        UUID activeRevision = UUID.randomUUID();
        seed(tenantId, spaceId, documentId, staleRevision, activeRevision);

        List<RetrievalCandidate> retained = guard.retainActive(
                tenantId,
                List.of(
                        candidate(tenantId, spaceId, documentId, staleRevision, "stale"),
                        candidate(tenantId, spaceId, documentId, activeRevision, "active"),
                        candidate(
                                tenantId,
                                new KnowledgeSpaceId("foreign-space"),
                                documentId,
                                activeRevision,
                                "wrong-space"
                        )
                )
        );

        assertFalse(guard.isActive(tenantId, documentId, staleRevision));
        assertTrue(guard.isActive(tenantId, documentId, activeRevision));
        assertEquals(1, retained.size());
        assertEquals(activeRevision, retained.getFirst().revisionId());

        PostgresIndexProjectionStore projectionStore = new PostgresIndexProjectionStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        UUID generationId = projectionStore.resolveActiveGeneration(
                tenantId,
                spaceId,
                new EmbeddingSpec("test", "model", 4),
                IndexPhysicalContract.baseline("generation-a"),
                "normalizer-a",
                "chunker-a",
                now
        );
        projectionStore.recordProjectionStatus(
                tenantId,
                generationId,
                documentId,
                activeRevision,
                ProjectionType.KEYWORD,
                ProjectionStatus.SUCCEEDED,
                now
        );
        projectionStore.recordProjectionStatus(
                tenantId,
                generationId,
                documentId,
                staleRevision,
                ProjectionType.KEYWORD,
                ProjectionStatus.SUCCEEDED,
                now.plusSeconds(1)
        );
        assertEquals(activeRevision, jdbc.queryForObject("""
                SELECT revision_id
                FROM document_index_projection
                WHERE tenant_id = ? AND generation_id = ? AND document_id = ?
                """, UUID.class, tenantId.value(), generationId, documentId.value()));
    }

    private static void seed(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID staleRevision,
            UUID activeRevision
    ) {
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Tenant A', 'ACTIVE', now(), now())
                """, tenantId.value());
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, status, created_at, updated_at)
                VALUES (?, ?, 'Engineering', 'ACTIVE', now(), now())
                """, tenantId.value(), spaceId.value());
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     status, created_at, updated_at)
                VALUES (?, 'api', ?, 'API', 'API', 'ACTIVE', now(), now())
                """, tenantId.value(), spaceId.value());
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id, source_type,
                     source_uri, title, status, authority, created_at, updated_at)
                VALUES (?, ?, ?, 'api', 'document', 'API', 'urn:test',
                        'Document', 'ACTIVE', 100, now(), now())
                """, tenantId.value(), documentId.value(), spaceId.value());
        jdbc.update("""
                INSERT INTO document_revision
                    (tenant_id, id, document_id, revision_number, content_hash,
                     media_type, language, parser_version, created_at)
                VALUES (?, ?, ?, 1, 'hash-stale', 'text/markdown', 'en', 'test', now()),
                       (?, ?, ?, 2, 'hash-active', 'text/markdown', 'en', 'test', now())
                """,
                tenantId.value(), staleRevision, documentId.value(),
                tenantId.value(), activeRevision, documentId.value());
        jdbc.update("""
                UPDATE knowledge_document
                SET active_revision_id = ?
                WHERE tenant_id = ? AND id = ?
                """, activeRevision, tenantId.value(), documentId.value());
    }

    private static RetrievalCandidate candidate(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            String content
    ) {
        return new RetrievalCandidate(
                UUID.randomUUID(),
                tenantId,
                spaceId,
                documentId,
                revisionId,
                RetrievalChannel.VECTOR,
                1,
                0.9D,
                "Document",
                List.of("Test"),
                content,
                "urn:test",
                Map.of()
        );
    }
}
