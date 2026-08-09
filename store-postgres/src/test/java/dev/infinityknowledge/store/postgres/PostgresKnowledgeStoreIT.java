package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
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

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实 PostgreSQL 验证 Flyway、ACL 查询和 Trace 事务。
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresKnowledgeStoreIT {
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");
    private static JdbcTemplate jdbc;
    private static PGSimpleDataSource dataSource;

    /**
     * 迁移真实数据库并创建 JDBC 测试入口。
     */
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
    }

    /**
     * 验证用户、角色和部门 ACL 只返回当前租户授权空间。
     */
    @Test
    void resolvesTenantScopedAcl() {
        seedTenantAndSpace();
        PostgresAccessPolicy policy = new PostgresAccessPolicy(
                new NamedParameterJdbcTemplate(dataSource)
        );
        var scope = policy.resolve(
                principal("tenant-a", "user-1"),
                Set.of(new KnowledgeSpaceId("engineering"))
        );

        assertEquals(Set.of(new KnowledgeSpaceId("engineering")), scope.spaceIds());
        assertEquals(AccessScope.Mode.ALL, scope.mode());
        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> policy.resolve(
                        principal("tenant-a", "user-2"),
                        Set.of(new KnowledgeSpaceId("engineering"))
                )
        );

        var databaseTime = Instant.parse("2026-08-03T00:00:00Z")
                .atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO knowledge_principal
                    (tenant_id, principal_id, principal_type, display_name,
                     status, created_at, updated_at)
                VALUES ('tenant-a', 'user-without-roles', 'USER', 'No Roles',
                        'ACTIVE', ?, ?)
                """, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_space_acl
                    (tenant_id, space_id, subject_type, subject_id,
                     permission, granted_by, created_at)
                VALUES ('tenant-a', 'engineering', 'ROLE', '__none__',
                        'READ', 'bootstrap', ?)
                """, databaseTime);
        PrincipalContext noRole = new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("user-without-roles"),
                Set.of(),
                Set.of(),
                false
        );
        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> policy.resolve(
                        noRole,
                        Set.of(new KnowledgeSpaceId("engineering"))
                )
        );
    }

    @Test
    void keepsSystemPrincipalInsideItsTenantBoundary() {
        seedTenantAndSpace();
        seedOtherTenantSpace();
        PostgresAccessPolicy policy = new PostgresAccessPolicy(
                new NamedParameterJdbcTemplate(dataSource)
        );
        PrincipalContext system = new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("index-worker"),
                Set.of(),
                Set.of(),
                true
        );

        AccessScope scope = policy.resolve(system, Set.of());

        assertEquals(new TenantId("tenant-a"), scope.tenantId());
        assertEquals(AccessScope.Mode.ALL, scope.mode());
        assertTrue(scope.spaceIds().contains(new KnowledgeSpaceId("engineering")));
        assertFalse(scope.spaceIds().contains(new KnowledgeSpaceId("tenant-b-secret")));
        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> policy.resolve(
                        system,
                        Set.of(new KnowledgeSpaceId("tenant-b-secret"))
                )
        );
    }

    @Test
    void deniesAccessWhenTenantIsSuspended() {
        String tenant = "tenant-suspended";
        var databaseTime = Instant.parse("2026-08-03T00:00:00Z")
                .atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Suspended tenant', 'SUSPENDED', ?, ?)
                """, tenant, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, status, created_at, updated_at)
                VALUES (?, 'engineering', 'Engineering', 'ACTIVE', ?, ?)
                """, tenant, databaseTime, databaseTime);
        PostgresAccessPolicy policy = new PostgresAccessPolicy(
                new NamedParameterJdbcTemplate(dataSource)
        );
        PrincipalContext system = new PrincipalContext(
                new TenantId(tenant),
                new PrincipalId("system-worker"),
                Set.of(),
                Set.of(),
                true
        );

        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> policy.resolve(system, Set.of())
        );
    }

    /**
     * 验证 Trace 主记录与阶段在同一事务中持久化。
     */
    @Test
    void appendsTraceAndStepsAtomically() {
        seedTenantAndSpace();
        PostgresTraceSink sink = new PostgresTraceSink(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        UUID traceId = UUID.randomUUID();
        sink.append(new RetrievalTrace(
                traceId,
                UUID.randomUUID(),
                new TenantId("tenant-a"),
                new PrincipalId("user-1"),
                "a".repeat(64),
                List.of(new RetrievalStepTrace(
                        "KEYWORD",
                        Duration.ofMillis(12),
                        1,
                        3,
                        "SUCCEEDED"
                )),
                Duration.ofMillis(20),
                2,
                Instant.parse("2026-07-26T00:00:00Z")
        ));

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM retrieval_trace WHERE id = ?",
                        Integer.class,
                        traceId
                )
        );
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM retrieval_trace_step WHERE trace_id = ?",
                        Integer.class,
                        traceId
                )
        );
    }

    /**
     * 验证关键词召回只读取活动修订，并保留标题、章节和来源引用。
     */
    @Test
    void retrievesOnlyActiveTenantScopedChunks() {
        seedTenantAndSpace();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        seedSearchDocument(documentId, revisionId, chunkId);
        PostgresKeywordRetriever retriever = new PostgresKeywordRetriever(
                new NamedParameterJdbcTemplate(dataSource)
        );
        KnowledgeQuery query = new KnowledgeQuery(
                UUID.randomUUID(),
                principal("tenant-a", "user-1"),
                "Redis 超时",
                Set.of(new KnowledgeSpaceId("engineering")),
                5,
                Map.of("language", "zh-CN")
        );
        RetrievalRequest request = new RetrievalRequest(
                query,
                new QueryPlan(
                        query.text(),
                        query.text(),
                        Set.of(RetrievalChannel.KEYWORD),
                        10
                ),
                new AccessScope(
                        new TenantId("tenant-a"),
                        Set.of(new KnowledgeSpaceId("engineering")),
                        Set.of()
                )
        );

        var candidates = retriever.retrieve(request);

        assertEquals(1, candidates.size());
        assertEquals(chunkId, candidates.getFirst().chunkId());
        assertEquals(List.of("用户中心", "故障处理"), candidates.getFirst().sectionPath());
        assertEquals("obsidian://vault/incident.md", candidates.getFirst().sourceUri());
    }

    @Test
    void deniedScopeDoesNotQueryKeywordDocuments() {
        PostgresKeywordRetriever retriever = new PostgresKeywordRetriever(
                new NamedParameterJdbcTemplate(dataSource)
        );
        KnowledgeQuery query = new KnowledgeQuery(
                UUID.randomUUID(),
                principal("tenant-a", "user-without-access"),
                "Redis timeout",
                Set.of(),
                5,
                Map.of()
        );
        RetrievalRequest request = new RetrievalRequest(
                query,
                new QueryPlan(
                        query.text(),
                        query.text(),
                        Set.of(RetrievalChannel.KEYWORD),
                        10
                ),
                AccessScope.denyAll(new TenantId("tenant-a"))
        );

        assertEquals(List.of(), retriever.retrieve(request));
    }

    /**
     * 验证文档、结构和 Chunk 原子发布，并对相同内容保持幂等。
     */
    @Test
    void writesAndPublishesRevisionIdempotently() {
        seedTenantAndSpace();
        seedApiConnector();
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        UUID elementId = UUID.randomUUID();
        var document = new KnowledgeDocument(
                documentId,
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                "登录排障",
                new SourceDescriptor(
                        "api-upload:engineering",
                        SourceType.API,
                        "login.md",
                        "https://example.invalid/login.md",
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                80,
                Map.of(),
                now,
                now
        );
        var revision = new DocumentRevision(
                revisionId,
                documentId,
                "c".repeat(64),
                "text/markdown",
                "zh-CN",
                "markdown-structure-v1",
                now
        );
        var element = new KnowledgeElement(
                elementId,
                revisionId,
                null,
                ElementType.PARAGRAPH,
                0,
                List.of("登录排障"),
                "检查 Redis 连接池。",
                Map.of()
        );
        var chunk = new KnowledgeChunk(
                UUID.randomUUID(),
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                documentId,
                revisionId,
                List.of(elementId),
                0,
                List.of("登录排障"),
                element.content(),
                "d".repeat(64),
                Map.of()
        );
        var writer = new PostgresKnowledgeWriter(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                JsonMapper.builder().build(),
                Set.of(ProjectionType.VECTOR, ProjectionType.KEYWORD)
        );
        var batch = new KnowledgeWriteBatch(
                document,
                revision,
                List.of(element),
                List.of(chunk)
        );

        var first = writer.write(batch);
        var second = writer.write(batch);

        assertEquals(true, first.changed());
        assertEquals(false, second.changed());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM document_revision
                WHERE tenant_id = 'tenant-a' AND document_id = ?
                """, Integer.class, documentId.value()));
        assertEquals(revisionId, jdbc.queryForObject("""
                SELECT active_revision_id FROM knowledge_document
                WHERE tenant_id = 'tenant-a' AND id = ?
                """, UUID.class, documentId.value()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM projection_job
                WHERE tenant_id = 'tenant-a'
                  AND revision_id = ?
                  AND projection_type = 'VECTOR'
                """, Integer.class, revisionId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM projection_job
                WHERE tenant_id = 'tenant-a'
                  AND revision_id = ?
                  AND projection_type = 'KEYWORD'
                """, Integer.class, revisionId));

        var queue = new PostgresProjectionJobQueue(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        var claimed = queue.claim(
                "worker-1",
                Set.of(ProjectionType.VECTOR),
                5,
                Duration.ofMinutes(1),
                now.plusSeconds(1)
        );
        assertEquals(1, claimed.size());
        assertEquals(ProjectionType.VECTOR, claimed.getFirst().projectionType());
        assertEquals(revisionId, claimed.getFirst().revisionId());

        var source = new PostgresProjectionSourceStore(jdbc).load(claimed.getFirst());
        assertEquals(documentId, source.document().id());
        assertEquals(1, source.chunks().size());
        assertEquals(revisionId, source.chunks().getFirst().revisionId());

        queue.complete(claimed.getFirst().id(), "worker-1", now.plusSeconds(2));
        assertEquals("SUCCEEDED", jdbc.queryForObject("""
                SELECT status FROM projection_job
                WHERE id = ?
                """, String.class, claimed.getFirst().id()));
    }

    /**
     * Verifies immutable generation identity and durable vector projection status.
     */
    @Test
    void persistsIndexGenerationAndProjectionStatus() {
        seedTenantAndSpace();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        seedSearchDocument(documentId, revisionId, UUID.randomUUID());
        jdbc.update("""
                UPDATE knowledge_chunk
                SET content = 'projection state verification only'
                WHERE tenant_id = 'tenant-a' AND document_id = ?
                """, documentId);
        var store = new PostgresIndexProjectionStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        Instant now = Instant.parse("2026-07-26T00:00:00Z");

        UUID first = store.resolveActiveGeneration(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                new EmbeddingSpec("zhipu", "embedding-3", 2048),
                "v1",
                "markdown-structure-v1",
                "heading-aware-v1",
                now
        );
        UUID second = store.resolveActiveGeneration(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                new EmbeddingSpec("zhipu", "embedding-3", 2048),
                "v1",
                "markdown-structure-v1",
                "heading-aware-v1",
                now
        );
        store.recordVectorStatus(
                new TenantId("tenant-a"),
                first,
                new DocumentId(documentId),
                revisionId,
                ProjectionStatus.SUCCEEDED,
                now
        );
        store.recordProjectionStatus(
                new TenantId("tenant-a"),
                first,
                new DocumentId(documentId),
                revisionId,
                ProjectionType.KEYWORD,
                ProjectionStatus.SUCCEEDED,
                now
        );

        assertEquals(first, second);
        assertEquals("ACTIVE", jdbc.queryForObject("""
                SELECT status FROM index_generation
                WHERE tenant_id = 'tenant-a' AND id = ?
                """, String.class, first));
        assertEquals("SUCCEEDED", jdbc.queryForObject("""
                SELECT vector_status FROM document_index_projection
                WHERE tenant_id = 'tenant-a'
                  AND generation_id = ?
                  AND document_id = ?
                """, String.class, first, documentId));
        assertEquals("SUCCEEDED", jdbc.queryForObject("""
                SELECT keyword_status FROM document_index_projection
                WHERE tenant_id = 'tenant-a'
                  AND generation_id = ?
                  AND document_id = ?
                """, String.class, first, documentId));
    }

    /**
     * Adapter 后启时可按空间安全回填当前活动修订，运行中的任务不会被抢占。
     */
    @Test
    void rebuildsActiveSpaceProjectionsWithoutResettingRunningJobs() {
        seedTenantAndSpace();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        seedSearchDocument(documentId, revisionId, UUID.randomUUID());
        var queue = new PostgresProjectionJobQueue(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        Instant now = Instant.parse("2026-08-03T05:00:00Z");

        assertEquals(2, queue.rebuildSpace(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                Set.of(ProjectionType.KEYWORD, ProjectionType.VECTOR),
                now
        ));
        var vector = queue.claim(
                "rebuild-worker",
                Set.of(ProjectionType.VECTOR),
                1,
                Duration.ofMinutes(1),
                now.plusSeconds(1)
        );
        assertEquals(1, vector.size());

        assertEquals(1, queue.rebuildSpace(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                Set.of(ProjectionType.KEYWORD, ProjectionType.VECTOR),
                now.plusSeconds(2)
        ));
        assertEquals("RUNNING", jdbc.queryForObject("""
                SELECT status FROM projection_job
                WHERE tenant_id = 'tenant-a'
                  AND revision_id = ?
                  AND projection_type = 'VECTOR'
                """, String.class, revisionId));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT status FROM projection_job
                WHERE tenant_id = 'tenant-a'
                  AND revision_id = ?
                  AND projection_type = 'KEYWORD'
                """, String.class, revisionId));
        assertEquals(documentId.value(), jdbc.queryForObject("""
                SELECT document_id FROM projection_job
                WHERE tenant_id = 'tenant-a'
                  AND revision_id = ?
                  AND projection_type = 'KEYWORD'
                """, UUID.class, revisionId));
    }

    /**
     * 管理面只展示并重试当前活动修订，不能重新执行同一文档的历史死信任务。
     */
    @Test
    void inspectsAndRetriesOnlyTheActiveRevisionProjection() {
        String tenant = "tenant-active-projection-admin";
        String space = "engineering";
        seedTenantSpaceAndConnector(tenant, space);
        var writer = writer(Set.of(ProjectionType.KEYWORD));
        DocumentId documentId = DocumentId.random();
        UUID revisionA = UUID.randomUUID();
        UUID revisionB = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-03T05:10:00Z");
        writer.write(batch(
                tenant, space, documentId, revisionA,
                "1".repeat(64), "projection-admin.md", "content A", now
        ));
        writer.write(batch(
                tenant, space, documentId, revisionB,
                "2".repeat(64), "projection-admin.md", "content B", now.plusSeconds(1)
        ));
        jdbc.update("""
                UPDATE projection_job
                   SET status = 'DEAD', last_error_code = 'TEST_FAILURE'
                 WHERE tenant_id = ? AND document_id = ?
                """, tenant, documentId.value());
        var queue = new PostgresProjectionJobQueue(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );

        var jobs = queue.findByDocument(new TenantId(tenant), documentId);
        boolean requeued = queue.requeueDead(
                new TenantId(tenant),
                documentId,
                ProjectionType.KEYWORD,
                now.plusSeconds(2)
        );

        assertEquals(1, jobs.size());
        assertEquals("DEAD", jobs.getFirst().status());
        assertTrue(requeued);
        assertEquals("DEAD", jdbc.queryForObject("""
                SELECT status FROM projection_job
                 WHERE tenant_id = ? AND revision_id = ?
                   AND projection_type = 'KEYWORD'
                """, String.class, tenant, revisionA));
        assertEquals("RETRY", jdbc.queryForObject("""
                SELECT status FROM projection_job
                 WHERE tenant_id = ? AND revision_id = ?
                   AND projection_type = 'KEYWORD'
                """, String.class, tenant, revisionB));
    }

    /**
     * 相同 externalId 在不同空间中必须解析为两个独立文档。
     */
    @Test
    void keepsSourceIdentityScopedToSpace() {
        String tenant = "tenant-space-identity";
        seedTenantSpaceAndConnector(tenant, "space-a");
        seedTenantSpaceAndConnector(tenant, "space-b");
        var writer = writer(Set.of());
        var catalog = new PostgresKnowledgeCatalog(jdbc);
        DocumentId documentA = DocumentId.random();
        DocumentId documentB = DocumentId.random();
        Instant now = Instant.parse("2026-08-03T01:00:00Z");

        writer.write(batch(
                tenant, "space-a", documentA, UUID.randomUUID(),
                "1".repeat(64), "shared.md", "space A content", now
        ));
        writer.write(batch(
                tenant, "space-b", documentB, UUID.randomUUID(),
                "2".repeat(64), "shared.md", "space B content", now
        ));

        assertNotEquals(documentA, documentB);
        assertEquals(documentA, catalog.findDocumentId(
                new TenantId(tenant),
                new KnowledgeSpaceId("space-a"),
                "api-upload:space-a",
                "shared.md"
        ).orElseThrow());
        assertEquals(documentB, catalog.findDocumentId(
                new TenantId(tenant),
                new KnowledgeSpaceId("space-b"),
                "api-upload:space-b",
                "shared.md"
        ).orElseThrow());
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_document
                WHERE tenant_id = ? AND external_id = 'shared.md'
                """, Integer.class, tenant));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*)
                FROM knowledge_chunk c
                JOIN knowledge_document d
                  ON d.tenant_id = c.tenant_id AND d.id = c.document_id
                WHERE c.tenant_id = ? AND c.space_id <> d.space_id
                """, Integer.class, tenant));
    }

    /**
     * A 到 B 再回到 A 必须重新激活历史 A，而不是把当前 B 当作幂等写入。
     */
    @Test
    void reactivatesHistoricalRevisionForAbaContentChange() {
        String tenant = "tenant-aba";
        String space = "engineering";
        seedTenantSpaceAndConnector(tenant, space);
        var writer = writer(Set.of(ProjectionType.VECTOR, ProjectionType.KEYWORD));
        DocumentId documentId = DocumentId.random();
        UUID revisionA = UUID.randomUUID();
        UUID revisionB = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-03T02:00:00Z");
        var batchA = batch(
                tenant, space, documentId, revisionA,
                "a".repeat(64), "aba.md", "content A", now
        );
        var batchB = batch(
                tenant, space, documentId, revisionB,
                "b".repeat(64), "aba.md", "content B", now.plusSeconds(1)
        );

        assertTrue(writer.write(batchA).changed());
        jdbc.update("""
                UPDATE projection_job
                SET status = 'SUCCEEDED', completed_at = updated_at
                WHERE tenant_id = ? AND revision_id = ?
                """, tenant, revisionA);
        assertTrue(writer.write(batchB).changed());
        var restored = writer.write(batchA);

        assertTrue(restored.changed());
        assertEquals(revisionA, restored.revisionId());
        assertEquals(revisionA, jdbc.queryForObject("""
                SELECT active_revision_id FROM knowledge_document
                WHERE tenant_id = ? AND id = ?
                """, UUID.class, tenant, documentId.value()));
        assertEquals(now.plusSeconds(1).atOffset(ZoneOffset.UTC), jdbc.queryForObject("""
                SELECT updated_at FROM knowledge_document
                WHERE tenant_id = ? AND id = ?
                """, OffsetDateTime.class, tenant, documentId.value()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM document_revision
                WHERE tenant_id = ? AND document_id = ?
                """, Integer.class, tenant, documentId.value()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM projection_job
                WHERE tenant_id = ? AND revision_id = ? AND status = 'PENDING'
                """, Integer.class, tenant, revisionA));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*)
                FROM knowledge_chunk c
                JOIN knowledge_document d
                  ON d.tenant_id = c.tenant_id
                 AND d.id = c.document_id
                 AND d.active_revision_id = c.revision_id
                WHERE c.tenant_id = ? AND c.document_id = ?
                  AND c.content = 'content A'
                """, Integer.class, tenant, documentId.value()));
    }

    /**
     * 正文不变但 Citation/排序元数据变化时必须重建外部投影。
     */
    @Test
    void republishesActiveRevisionWhenDocumentProjectionMetadataChanges() {
        String tenant = "tenant-document-metadata";
        String space = "engineering";
        seedTenantSpaceAndConnector(tenant, space);
        var writer = writer(Set.of(ProjectionType.VECTOR, ProjectionType.KEYWORD));
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-03T02:30:00Z");
        var original = batch(
                tenant, space, documentId, revisionId,
                "e".repeat(64), "metadata.md", "stable content", now
        );
        assertTrue(writer.write(original).changed());
        jdbc.update("""
                UPDATE projection_job
                   SET status = 'SUCCEEDED', completed_at = updated_at
                 WHERE tenant_id = ? AND revision_id = ?
                """, tenant, revisionId);

        var oldDocument = original.document();
        var changedDocument = new KnowledgeDocument(
                oldDocument.id(),
                oldDocument.tenantId(),
                oldDocument.spaceId(),
                "Updated title",
                new SourceDescriptor(
                        oldDocument.source().connectorId(),
                        oldDocument.source().type(),
                        oldDocument.source().externalId(),
                        "https://example.invalid/updated-metadata.md",
                        oldDocument.source().attributes()
                ),
                oldDocument.status(),
                95,
                Map.of("owner", "platform"),
                oldDocument.createdAt(),
                now.plusSeconds(1)
        );
        var updated = new KnowledgeWriteBatch(
                changedDocument,
                original.revision(),
                original.elements(),
                original.chunks()
        );

        var result = writer.write(updated);

        assertTrue(result.changed());
        assertEquals(revisionId, result.revisionId());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM document_revision
                 WHERE tenant_id = ? AND document_id = ?
                """, Integer.class, tenant, documentId.value()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM projection_job
                 WHERE tenant_id = ? AND revision_id = ? AND status = 'PENDING'
                """, Integer.class, tenant, revisionId));
        assertEquals("Updated title", jdbc.queryForObject("""
                SELECT title FROM knowledge_document
                 WHERE tenant_id = ? AND id = ?
                """, String.class, tenant, documentId.value()));
        assertEquals(2L, jdbc.queryForObject("""
                SELECT version FROM knowledge_document
                 WHERE tenant_id = ? AND id = ?
                """, Long.class, tenant, documentId.value()));
    }

    /**
     * 已归档文档用相同正文重新发布时必须恢复 ACTIVE 并重建投影。
     */
    @Test
    void reactivatesArchivedDocumentWhenTheSameRevisionIsPublished() {
        String tenant = "tenant-archived-reactivation";
        String space = "engineering";
        seedTenantSpaceAndConnector(tenant, space);
        var writer = writer(Set.of(ProjectionType.KEYWORD));
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-03T02:45:00Z");
        var original = batch(
                tenant, space, documentId, revisionId,
                "f".repeat(64), "archived.md", "stable content", now
        );
        assertTrue(writer.write(original).changed());
        jdbc.update("""
                UPDATE knowledge_document SET status = 'ARCHIVED'
                 WHERE tenant_id = ? AND id = ?
                """, tenant, documentId.value());
        jdbc.update("""
                UPDATE projection_job
                   SET status = 'SUCCEEDED', completed_at = updated_at
                 WHERE tenant_id = ? AND revision_id = ?
                """, tenant, revisionId);

        var result = writer.write(original);

        assertTrue(result.changed());
        assertEquals("ACTIVE", jdbc.queryForObject("""
                SELECT status FROM knowledge_document
                 WHERE tenant_id = ? AND id = ?
                """, String.class, tenant, documentId.value()));
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT status FROM projection_job
                 WHERE tenant_id = ? AND revision_id = ?
                """, String.class, tenant, revisionId));
    }

    /**
     * 相同正文在语言契约变化后必须生成独立修订，避免过滤继续使用旧语言。
     */
    @Test
    void createsDistinctRevisionWhenLanguageChangesWithoutContentChanges() {
        String tenant = "tenant-language-revision";
        String space = "engineering";
        seedTenantSpaceAndConnector(tenant, space);
        var writer = writer(Set.of());
        DocumentId documentId = DocumentId.random();
        Instant now = Instant.parse("2026-08-03T02:50:00Z");
        String contentHash = "7".repeat(64);
        var chinese = batchWithLanguage(
                tenant, space, documentId, UUID.randomUUID(), contentHash,
                "language.md", "stable content", "zh-CN", now
        );
        var english = batchWithLanguage(
                tenant, space, documentId, UUID.randomUUID(), contentHash,
                "language.md", "stable content", "en-US", now.plusSeconds(1)
        );

        assertTrue(writer.write(chinese).changed());
        var result = writer.write(english);

        assertTrue(result.changed());
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM document_revision
                WHERE tenant_id = ? AND document_id = ?
                """, Integer.class, tenant, documentId.value()));
        assertEquals("en-US", jdbc.queryForObject("""
                SELECT r.language
                  FROM knowledge_document d
                  JOIN document_revision r
                    ON r.tenant_id = d.tenant_id
                   AND r.id = d.active_revision_id
                 WHERE d.tenant_id = ? AND d.id = ?
                """, String.class, tenant, documentId.value()));
    }

    /**
     * 并发新正文的最终修订号只能由 Writer 锁事务分配，且必须唯一单调。
     */
    @Test
    void allocatesRevisionNumbersUnderDocumentLock() throws Exception {
        String tenant = "tenant-concurrent-revision";
        String space = "engineering";
        seedTenantSpaceAndConnector(tenant, space);
        var writer = writer(Set.of());
        DocumentId documentId = DocumentId.random();
        Instant now = Instant.parse("2026-08-03T03:00:00Z");
        var firstBatch = batch(
                tenant, space, documentId, UUID.randomUUID(),
                "c".repeat(64), "concurrent.md", "first body", now
        );
        var secondBatch = batch(
                tenant, space, documentId, UUID.randomUUID(),
                "d".repeat(64), "concurrent.md", "second body", now
        );
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return writer.write(firstBatch);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return writer.write(secondBatch);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(first.get(30, TimeUnit.SECONDS).changed());
            assertTrue(second.get(30, TimeUnit.SECONDS).changed());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of(1L, 2L), jdbc.queryForList("""
                SELECT revision_number FROM document_revision
                WHERE tenant_id = ? AND document_id = ?
                ORDER BY revision_number
                """, Long.class, tenant, documentId.value()));
    }

    /**
     * 幂等创建测试租户、主体、空间和用户 ACL。
     */
    private void seedTenantAndSpace() {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        var databaseTime = now.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES ('tenant-a', 'Tenant A', 'ACTIVE', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_principal
                    (tenant_id, principal_id, principal_type, display_name,
                     status, created_at, updated_at)
                VALUES ('tenant-a', 'user-1', 'USER', 'User One',
                        'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, principal_id) DO NOTHING
                """, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, status, created_at, updated_at)
                VALUES ('tenant-a', 'engineering', 'Engineering',
                        'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, id) DO NOTHING
                """, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_space_acl
                    (tenant_id, space_id, subject_type, subject_id,
                     permission, granted_by, created_at)
                VALUES ('tenant-a', 'engineering', 'USER', 'user-1',
                        'READ', 'bootstrap', ?)
                ON CONFLICT DO NOTHING
                """, databaseTime);
    }

    private void seedOtherTenantSpace() {
        var databaseTime = Instant.parse("2026-07-26T00:00:00Z").atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES ('tenant-b', 'Tenant B', 'ACTIVE', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, status, created_at, updated_at)
                VALUES ('tenant-b', 'tenant-b-secret', 'Tenant B Secret',
                        'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, id) DO NOTHING
                """, databaseTime, databaseTime);
    }

    /**
     * 创建一个具有活动修订和检索 Chunk 的真实文档。
     */
    private void seedSearchDocument(UUID documentId, UUID revisionId, UUID chunkId) {
        var databaseTime = Instant.parse("2026-07-26T00:00:00Z").atOffset(ZoneOffset.UTC);
        String externalId = "incident-" + documentId + ".md";
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, created_at, updated_at)
                VALUES ('tenant-a', 'obsidian-main', 'engineering', 'OBSIDIAN',
                        'Main Vault', '{}'::jsonb, 'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, id) DO NOTHING
                """, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_document
                    (tenant_id, id, space_id, connector_id, external_id, source_type,
                     source_uri, title, status, authority, metadata_json,
                     active_revision_id, version, created_at, updated_at)
                VALUES ('tenant-a', ?, 'engineering', 'obsidian-main', ?,
                        'OBSIDIAN', 'obsidian://vault/incident.md', 'Redis 故障处理',
                        'ACTIVE', 80, '{}'::jsonb, NULL, 1, ?, ?)
                """, documentId, externalId, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO document_revision
                    (tenant_id, id, document_id, revision_number, content_hash,
                     media_type, language, parser_version, object_uri, created_at)
                VALUES ('tenant-a', ?, ?, 1, ?, 'text/markdown', 'zh-CN',
                        'markdown-structure-v1', NULL, ?)
                """, revisionId, documentId, "a".repeat(64), databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_chunk
                    (tenant_id, id, space_id, document_id, revision_id, ordinal,
                     section_path_json, element_ids_json, content, content_hash,
                     metadata_json, created_at)
                VALUES ('tenant-a', ?, 'engineering', ?, ?, 0,
                        '["用户中心", "故障处理"]'::jsonb, '[]'::jsonb,
                        'Redis 超时需要检查连接池和网络配置。', ?,
                        '{}'::jsonb, ?)
                """, chunkId, documentId, revisionId, "b".repeat(64), databaseTime);
        jdbc.update("""
                UPDATE knowledge_document
                SET active_revision_id = ?
                WHERE tenant_id = 'tenant-a' AND id = ?
                """, revisionId, documentId);
    }

    /**
     * 幂等创建 API 上传连接器。
     */
    private void seedApiConnector() {
        var databaseTime = Instant.parse("2026-07-26T00:00:00Z").atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, created_at, updated_at)
                VALUES ('tenant-a', 'api-upload:engineering', 'engineering', 'API',
                        'API Upload', '{}'::jsonb, 'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, id) DO NOTHING
                """, databaseTime, databaseTime);
    }

    /**
     * 创建一致性用例独占的租户、空间和空间级 API Connector。
     */
    private void seedTenantSpaceAndConnector(String tenant, String space) {
        var databaseTime = Instant.parse("2026-08-03T00:00:00Z").atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, tenant, tenant, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO knowledge_space
                    (tenant_id, id, name, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, id) DO NOTHING
                """, tenant, space, space, databaseTime, databaseTime);
        jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, created_at, updated_at)
                VALUES (?, ?, ?, 'API', 'API Upload', '{}'::jsonb, 'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, id) DO NOTHING
                """, tenant, "api-upload:" + space, space, databaseTime, databaseTime);
    }

    /**
     * 创建一个最小但完整的 Markdown 写入批次。
     */
    private KnowledgeWriteBatch batch(
            String tenant,
            String space,
            DocumentId documentId,
            UUID revisionId,
            String contentHash,
            String externalId,
            String content,
            Instant now
    ) {
        return batchWithLanguage(
                tenant,
                space,
                documentId,
                revisionId,
                contentHash,
                externalId,
                content,
                "zh-CN",
                now
        );
    }

    private KnowledgeWriteBatch batchWithLanguage(
            String tenant,
            String space,
            DocumentId documentId,
            UUID revisionId,
            String contentHash,
            String externalId,
            String content,
            String language,
            Instant now
    ) {
        var tenantId = new TenantId(tenant);
        var spaceId = new KnowledgeSpaceId(space);
        var document = new KnowledgeDocument(
                documentId,
                tenantId,
                spaceId,
                externalId,
                new SourceDescriptor(
                        "api-upload:" + space,
                        SourceType.API,
                        externalId,
                        "https://example.invalid/" + externalId,
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                80,
                Map.of(),
                now,
                now
        );
        var revision = new DocumentRevision(
                revisionId,
                documentId,
                contentHash,
                "text/markdown",
                language,
                "markdown-structure-v1",
                now
        );
        UUID elementId = UUID.randomUUID();
        var element = new KnowledgeElement(
                elementId,
                revisionId,
                null,
                ElementType.PARAGRAPH,
                0,
                List.of(externalId),
                content,
                Map.of()
        );
        var chunk = new KnowledgeChunk(
                UUID.randomUUID(),
                tenantId,
                spaceId,
                documentId,
                revisionId,
                List.of(elementId),
                0,
                List.of(externalId),
                content,
                contentHash,
                Map.of()
        );
        return new KnowledgeWriteBatch(document, revision, List.of(element), List.of(chunk));
    }

    private PostgresKnowledgeWriter writer(Set<ProjectionType> projections) {
        return new PostgresKnowledgeWriter(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                JsonMapper.builder().build(),
                projections
        );
    }

    /**
     * 创建测试主体。
     *
     * @param tenant 租户
     * @param principal 主体
     * @return 主体上下文
     */
    private PrincipalContext principal(String tenant, String principal) {
        return new PrincipalContext(
                new TenantId(tenant),
                new PrincipalId(principal),
                Set.of("knowledge-reader"),
                Set.of("engineering"),
                false
        );
    }
}
