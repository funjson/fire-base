package dev.infinityknowledge.store.postgres.extraction;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.extraction.ActiveExtractionRunException;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionPublicationInProgressException;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.BeginRequest;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ChunkDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.CleaningDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemStatus;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunStatus;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.SourceAsset;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 V18 的全局 UUID、空间 Single-flight 与租户查询边界。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresExtractionRunStoreIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private PostgresExtractionRunStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        seedTenantAndSpace(jdbc, "tenant-a", "space-a");
        seedTenantAndSpace(jdbc, "tenant-b", "space-b");
        store = new PostgresExtractionRunStore(
                new NamedParameterJdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    @Test
    void rejectsTheSameRunUuidAcrossTenantsAndKeepsQueriesTenantScoped() {
        UUID runId = UUID.randomUUID();
        store.begin(request(runId, "tenant-a", "space-a"));

        assertThrows(
                ActiveExtractionRunException.class,
                () -> store.begin(request(runId, "tenant-b", "space-b"))
        );
        var persisted = store.find(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                runId
        ).orElseThrow();
        assertEquals(snapshot(), persisted.configSnapshot());
        assertFalse(store.find(
                new TenantId("tenant-b"),
                new KnowledgeSpaceId("space-b"),
                runId
        ).isPresent());
    }

    @Test
    void allowsOnlyOneActiveRunInTheSameSpace() {
        store.begin(request(UUID.randomUUID(), "tenant-a", "space-a"));

        assertThrows(
                ActiveExtractionRunException.class,
                () -> store.begin(request(UUID.randomUUID(), "tenant-a", "space-a"))
        );
    }

    @Test
    void creationFailureTerminatesAlreadyAppendedItemsInTheSameTransaction() {
        UUID runId = UUID.randomUUID();
        store.begin(request(runId, "tenant-a", "space-a"));
        store.appendSource(
                runId,
                source("tenant-a", "space-a"),
                publication(),
                NOW
        );

        store.failCreation(runId, "SOURCE_UPLOAD_FAILED", NOW.plusSeconds(1));

        var failed = store.find(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                runId
        ).orElseThrow();
        assertEquals(RunStatus.FAILED, failed.status());
        assertEquals(ItemStatus.FAILED, failed.items().getFirst().status());
        assertEquals("SOURCE_UPLOAD_FAILED", failed.items().getFirst().errorCode());
    }

    @Test
    void recoversUnsealedMultipartWithoutDeletingItsSourceCatalog() {
        UUID runId = UUID.randomUUID();
        store.begin(request(runId, "tenant-a", "space-a"));
        SourceAsset source = source("tenant-a", "space-a");
        store.appendSource(runId, source, publication(), NOW);

        int recovered = store.failInterruptedUploads(
                NOW.plusSeconds(1),
                NOW.plusSeconds(2),
                10
        );

        var failed = store.find(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                runId
        ).orElseThrow();
        assertEquals(1, recovered);
        assertEquals(RunStatus.FAILED, failed.status());
        assertEquals("UPLOAD_INTERRUPTED", failed.errorCode());
        assertEquals(source.id(), failed.items().getFirst().sourceAsset().id());
        assertEquals(ItemStatus.FAILED, failed.items().getFirst().status());
    }

    @Test
    void receivingCancellationPreventsLaterAppendAndSeal() {
        UUID runId = UUID.randomUUID();
        store.begin(request(runId, "tenant-a", "space-a"));
        store.requestCancel(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                runId,
                NOW.plusSeconds(1)
        );

        assertThrows(
                IllegalStateException.class,
                () -> store.appendSource(
                        runId,
                        source("tenant-a", "space-a"),
                        publication(),
                        NOW.plusSeconds(2)
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> store.seal(runId, 1, NOW.plusSeconds(2))
        );
        var cancelled = store.find(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                runId
        ).orElseThrow();
        assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertTrue(cancelled.items().isEmpty());
    }

    @Test
    void persistsAndRestoresTypedCleanAndChunkDiagnostics() {
        UUID runId = UUID.randomUUID();
        store.begin(request(runId, "tenant-a", "space-a"));
        store.appendSource(
                runId,
                source("tenant-a", "space-a"),
                publication(),
                NOW
        );
        store.seal(runId, 1, NOW.plusSeconds(1));
        var claimed = store.claimNext(
                ExtractionMode.TEST_ONLY,
                "worker-a",
                NOW.minusSeconds(60),
                NOW.plusSeconds(2)
        ).orElseThrow();
        UUID itemId = claimed.items().getFirst().id();
        assertTrue(store.startItem(runId, itemId, "worker-a", NOW.plusSeconds(3)));

        ItemDiagnostics expected = diagnostics();
        assertTrue(store.succeedItem(
                runId,
                itemId,
                "worker-a",
                expected,
                null,
                NOW.plusSeconds(4)
        ));

        var actual = store.find(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                runId
        ).orElseThrow().items().getFirst().diagnostics();
        assertEquals(expected, actual);
    }

    @Test
    void rejectsCancellationWhilePublishingAndAcceptsBothSuccessClasses() {
        UUID runId = UUID.randomUUID();
        store.begin(request(runId, "tenant-a", "space-a", ExtractionMode.INGEST));
        store.appendSource(runId, source("tenant-a", "space-a"), publication(), NOW);
        store.appendSource(runId, source("tenant-a", "space-a"), publication(), NOW);
        store.seal(runId, 2, NOW.plusSeconds(1));
        var claimed = store.claimNext(
                ExtractionMode.INGEST,
                "worker-ingest",
                NOW.minusSeconds(60),
                NOW.plusSeconds(2)
        ).orElseThrow();
        UUID publishedItemId = claimed.items().get(0).id();
        UUID duplicateItemId = claimed.items().get(1).id();
        PublishedOutput output = seedPublishedOutput(jdbc);

        assertTrue(store.startItem(
                runId,
                publishedItemId,
                "worker-ingest",
                NOW.plusSeconds(3)
        ));
        assertTrue(store.beginPublication(
                runId,
                publishedItemId,
                "worker-ingest",
                NOW.plusSeconds(4)
        ));
        assertThrows(
                ExtractionPublicationInProgressException.class,
                () -> store.requestCancel(
                        new TenantId("tenant-a"),
                        new KnowledgeSpaceId("space-a"),
                        runId,
                        NOW.plusSeconds(5)
                )
        );
        assertTrue(store.succeedPublishedItem(
                runId,
                publishedItemId,
                "worker-ingest",
                diagnostics(),
                null,
                output.documentId(),
                output.revisionId(),
                NOW.plusSeconds(6)
        ));

        assertTrue(store.startItem(
                runId,
                duplicateItemId,
                "worker-ingest",
                NOW.plusSeconds(7)
        ));
        assertTrue(store.skipDuplicateItem(
                runId,
                duplicateItemId,
                "worker-ingest",
                output.documentId(),
                output.revisionId(),
                NOW.plusSeconds(8)
        ));
        assertTrue(store.finishRun(
                runId,
                "worker-ingest",
                RunStatus.SUCCEEDED,
                null,
                null,
                NOW.plusSeconds(9)
        ));

        var finished = store.find(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                runId
        ).orElseThrow();
        assertEquals(RunStatus.SUCCEEDED, finished.status());
        assertEquals(ItemStatus.SUCCEEDED, finished.items().get(0).status());
        assertEquals(ItemStatus.SKIPPED_DUPLICATE, finished.items().get(1).status());
        assertEquals(output.documentId(), finished.items().get(0).documentId());
        assertEquals(output.revisionId(), finished.items().get(1).revisionId());
    }

    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");

    private static SourceAsset source(String tenant, String space) {
        UUID id = UUID.randomUUID();
        return new SourceAsset(
                id,
                new TenantId(tenant),
                new KnowledgeSpaceId(space),
                "extraction-test-" + id,
                "storage-" + id,
                "runbook.md",
                "text/markdown",
                8L,
                "a".repeat(64),
                NOW
        );
    }

    private static dev.infinityknowledge.spi.extraction.ExtractionRunStore
            .PublicationAttributes publication() {
        return new dev.infinityknowledge.spi.extraction.ExtractionRunStore
                .PublicationAttributes("runbook.md", "Runbook", 80);
    }

    private static BeginRequest request(UUID runId, String tenant, String space) {
        return request(runId, tenant, space, ExtractionMode.TEST_ONLY);
    }

    private static BeginRequest request(
            UUID runId,
            String tenant,
            String space,
            ExtractionMode mode
    ) {
        return new BeginRequest(
                runId,
                new TenantId(tenant),
                new KnowledgeSpaceId(space),
                mode,
                "zh-CN",
                1L,
                snapshot(),
                null,
                null,
                new PrincipalId("admin"),
                NOW
        );
    }

    private static ItemDiagnostics diagnostics() {
        return new ItemDiagnostics(
                "markdown-structure",
                "pipeline-v7:test",
                3,
                2,
                4,
                5,
                6,
                new CleaningDiagnostics(2, 1, Map.of("FOOTER_METADATA_ONLY", 1)),
                new ChunkDiagnostics(
                        1, 1, 3, 1, 1, 1,
                        1, 1, 0, 1, 0, 0,
                        2, 10, 15.0D, 20
                )
        );
    }

    private static PublishedOutput seedPublishedOutput(JdbcTemplate jdbc) {
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, version, created_at, updated_at)
                VALUES ('tenant-a', 'api-upload', 'space-a', 'API_UPLOAD',
                        'API Upload', '{}'::jsonb, 'ACTIVE', 0, ?, ?)
                """, now, now);
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id,
                     source_type, source_uri, title, status, authority,
                     metadata_json, version, created_at, updated_at)
                VALUES ('tenant-a', ?, 'space-a', 'api-upload', ?, 'FILE',
                        ?, 'Runbook', 'ACTIVE', 80, '{}'::jsonb, 1, ?, ?)
                """, documentId, documentId.toString(), "oss://" + documentId, now, now);
        jdbc.update("""
                INSERT INTO document_revision
                    (tenant_id, id, document_id, revision_number, content_hash,
                     media_type, language, parser_version, object_uri, created_at)
                VALUES ('tenant-a', ?, ?, 1, ?, 'text/markdown', 'zh-CN',
                        'pipeline-v7', ?, ?)
                """, revisionId, documentId, "b".repeat(64),
                "oss://" + documentId, now);
        jdbc.update("""
                UPDATE knowledge_document
                   SET active_revision_id = ?
                 WHERE tenant_id = 'tenant-a' AND id = ?
                """, revisionId, documentId);
        return new PublishedOutput(new DocumentId(documentId), revisionId);
    }

    private record PublishedOutput(DocumentId documentId, UUID revisionId) {
    }

    private static ExtractionConfigSnapshot snapshot() {
        return new ExtractionConfigSnapshot(
                DocumentProcessingContract.create(
                        "pipeline-v6",
                        "normalizer-schema-v1",
                        Map.of("text/markdown", "markdown-structure/v1"),
                        "cleaner-v1",
                        "chunker-v1"
                ),
                "identity-bytes-v1",
                Map.of("text/markdown", "markdown-structure"),
                new ExtractionConfigSnapshot.Cleaning(
                        dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
                                .CleaningAction.KEEP,
                        dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
                                .CleaningAction.KEEP,
                        dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
                                .CleaningAction.KEEP,
                        dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
                                .CleaningAction.KEEP,
                        dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
                                .CleaningAction.REMOVE
                ),
                new ExtractionConfigSnapshot.Chunker(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        64,
                        128,
                        256,
                        16,
                        "{}"
                ),
                "0".repeat(64)
        );
    }

    private static void seedTenantAndSpace(
            JdbcTemplate jdbc,
            String tenant,
            String space
    ) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-21T08:00:00Z");
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?)
                """, tenant, tenant, now, now);
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, description, status, version,
                     created_at, updated_at)
                VALUES (?, ?, ?, '', 'ACTIVE', 0, ?, ?)
                """, tenant, space, space, now, now);
    }
}
