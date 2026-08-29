package dev.infinityknowledge.store.postgres.ingestion;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.store.postgres.PostgresKnowledgeGovernanceStore;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 PostgreSQL 验证 Space 配置只创建一次且保持租户隔离。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresSpaceDocumentProcessingConfigStoreIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static PostgresKnowledgeGovernanceStore governance;
    private static PostgresSpaceDocumentProcessingConfigStore store;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

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
        var namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        governance = new PostgresKnowledgeGovernanceStore(namedJdbc);
        store = new PostgresSpaceDocumentProcessingConfigStore(namedJdbc);
    }

    @Test
    void governanceAndConfigRowsRollbackAsOneCreationTransaction() {
        PrincipalContext admin = admin("tenant-config-rollback", "admin-config-rollback");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering-config-rollback");
        var requested = config(admin, spaceId, 512);

        assertThrows(IllegalStateException.class, () ->
                transactions.executeWithoutResult(status -> {
                    governance.createSpace(
                            admin,
                            spaceId,
                            "Engineering",
                            "Document processing rollback test space",
                            Instant.now()
                    );
                    assertEquals(
                            SpaceDocumentProcessingConfigStore.CreateOutcome.CREATED,
                            store.createImmutable(requested)
                    );
                    throw new IllegalStateException("simulate creation failure");
                })
        );

        assertEquals(0, count("knowledge_space", admin, spaceId));
        assertEquals(0, count("knowledge_space_acl", admin, spaceId));
        assertEquals(0, count("connector_instance", admin, spaceId));
        assertEquals(0, count("space_document_processing_config", admin, spaceId));
    }

    @Test
    void concurrentIdenticalCreationProducesOneCompleteDefinition() throws Exception {
        PrincipalContext admin = admin("tenant-config-concurrent", "admin-config-concurrent");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering-config-concurrent");
        var requested = config(admin, spaceId, 512);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> createDefinition(
                    admin,
                    spaceId,
                    requested,
                    start
            ));
            var second = executor.submit(() -> createDefinition(
                    admin,
                    spaceId,
                    requested,
                    start
            ));
            start.countDown();

            boolean firstCreated = first.get(15, TimeUnit.SECONDS);
            boolean secondCreated = second.get(15, TimeUnit.SECONDS);

            assertNotEquals(firstCreated, secondCreated);
            assertEquals(1, count("knowledge_space", admin, spaceId));
            assertEquals(1, count("knowledge_space_acl", admin, spaceId));
            assertEquals(1, count("connector_instance", admin, spaceId));
            assertEquals(1, count("space_document_processing_config", admin, spaceId));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void createsAndReadsImmutableConfiguration() {
        PrincipalContext admin = admin("tenant-config-create", "admin-config-create");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering-config-create");
        createSpace(admin, spaceId, "Engineering");
        var requested = config(admin, spaceId, 512);

        assertEquals(
                SpaceDocumentProcessingConfigStore.CreateOutcome.CREATED,
                store.createImmutable(requested)
        );

        var saved = store.find(admin.tenantId(), spaceId).orElseThrow();
        assertEquals(1L, saved.version());
        assertEquals(admin.principalId(), saved.updatedBy());
        assertEquals(requested.parserSelections(), saved.parserSelections());
        assertEquals(requested.cleaning(), saved.cleaning());
        assertEquals(requested.chunker(), saved.chunker());
        assertEquals(requested.processingContract(), saved.processingContract());
    }

    @Test
    void secondCreateNeverOverwritesExistingConfiguration() {
        PrincipalContext admin = admin("tenant-config-once", "admin-config-once");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering-config-once");
        createSpace(admin, spaceId, "Engineering");
        var first = config(admin, spaceId, 512);
        var different = config(admin, spaceId, 768);

        assertEquals(
                SpaceDocumentProcessingConfigStore.CreateOutcome.CREATED,
                store.createImmutable(first)
        );
        assertEquals(
                SpaceDocumentProcessingConfigStore.CreateOutcome.ALREADY_EXISTS,
                store.createImmutable(different)
        );

        assertEquals(
                first.chunker(),
                store.find(admin.tenantId(), spaceId).orElseThrow().chunker()
        );
    }

    @Test
    void neverReturnsAnotherTenantsConfiguration() {
        PrincipalContext tenantA = admin("tenant-config-a", "admin-a");
        PrincipalContext tenantB = admin("tenant-config-b", "admin-b");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("shared-config-id");
        createSpace(tenantA, spaceId, "Tenant A");
        createSpace(tenantB, spaceId, "Tenant B");
        store.createImmutable(config(tenantA, spaceId, 512));

        assertTrue(store.find(tenantB.tenantId(), spaceId).isEmpty());
    }

    private static void createSpace(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            String name
    ) {
        governance.createSpace(
                principal,
                spaceId,
                name,
                "Document processing configuration test space",
                Instant.parse("2026-08-16T00:00:00Z")
        );
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig config(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            int targetTokens
    ) {
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                principal.tenantId(),
                spaceId,
                Map.of(
                        "application/pdf", "pdfbox-page",
                        "text/markdown", "markdown-structure"
                ),
                new SpaceDocumentProcessingConfigStore.CleaningConfiguration(
                        SpaceDocumentProcessingConfigStore.CleaningAction.REMOVE,
                        SpaceDocumentProcessingConfigStore.CleaningAction.METADATA_ONLY,
                        SpaceDocumentProcessingConfigStore.CleaningAction.REMOVE,
                        SpaceDocumentProcessingConfigStore.CleaningAction.METADATA_ONLY,
                        SpaceDocumentProcessingConfigStore.CleaningAction.KEEP
                ),
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        128,
                        targetTokens,
                        1_024,
                        32,
                        "{}"
                ),
                processingContract(targetTokens),
                0L,
                principal.principalId(),
                Instant.EPOCH
        );
    }

    /** 构造与测试配置绑定的完整实现合同，验证各合同字段和总指纹可无损往返。 */
    private static DocumentProcessingContract processingContract(int targetTokens) {
        return DocumentProcessingContract.create(
                "pipeline-test-v1",
                "normalizer-schema-test-v1",
                Map.of(
                        "application/pdf", "pdfbox-page:test-v1",
                        "text/markdown", "markdown-structure:test-v1"
                ),
                "cleaner-test-v1",
                "structural-test-v1:target=" + targetTokens
        );
    }

    private static int count(
            String table,
            PrincipalContext principal,
            KnowledgeSpaceId spaceId
    ) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table
                        + " WHERE tenant_id = ? AND "
                        + ("knowledge_space".equals(table) ? "id" : "space_id")
                        + " = ?",
                Integer.class,
                principal.tenantId().value(),
                spaceId.value()
        );
    }

    private static boolean createDefinition(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig requested,
            CountDownLatch start
    ) throws InterruptedException {
        start.await();
        Boolean created = transactions.execute(status -> {
            var outcome = governance.createSpace(
                    principal,
                    spaceId,
                    "Engineering",
                    "Concurrent document processing test space",
                    Instant.now()
            );
            if (outcome.created()) {
                assertEquals(
                        SpaceDocumentProcessingConfigStore.CreateOutcome.CREATED,
                        store.createImmutable(requested)
                );
            } else {
                assertTrue(store.find(principal.tenantId(), spaceId).isPresent());
            }
            return outcome.created();
        });
        return Boolean.TRUE.equals(created);
    }

    private static PrincipalContext admin(String tenantId, String principalId) {
        return new PrincipalContext(
                new TenantId(tenantId),
                new PrincipalId(principalId),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }
}
