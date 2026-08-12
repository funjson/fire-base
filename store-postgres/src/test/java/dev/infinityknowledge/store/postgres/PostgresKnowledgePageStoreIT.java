package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.wiki.KnowledgePageStatus;
import dev.infinityknowledge.domain.wiki.PageSourceReference;
import dev.infinityknowledge.spi.wiki.KnowledgePageCompiler;
import dev.infinityknowledge.spi.wiki.KnowledgePageStore;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies draft idempotency, review publication, provenance and tenant isolation. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresKnowledgePageStoreIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static PostgresKnowledgePageStore store;

    @BeforeAll
    static void migrateAndSeed() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        store = new PostgresKnowledgePageStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
        OffsetDateTime now = OffsetDateTime.parse("2026-08-11T00:00:00Z");
        for (String tenant : List.of("tenant-a", "tenant-b")) {
            jdbc.update("""
                    INSERT INTO knowledge_tenant
                        (id, display_name, status, created_at, updated_at)
                    VALUES (?, ?, 'ACTIVE', ?, ?)
                    """, tenant, tenant, now, now);
            jdbc.update("""
                    INSERT INTO knowledge_space
                        (tenant_id, id, name, description, status, version,
                         created_at, updated_at)
                    VALUES (?, 'engineering', 'Engineering', '', 'ACTIVE', 0, ?, ?)
                    """, tenant, now, now);
        }
    }

    @Test
    void publishesReviewedDraftAndKeepsTenantBoundary() {
        TenantId tenant = new TenantId("tenant-a");
        KnowledgeSpaceId space = new KnowledgeSpaceId("engineering");
        UUID sourceRevision = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-11T01:00:00Z");
        KnowledgePageStore.DraftCommand command = new KnowledgePageStore.DraftCommand(
                tenant,
                space,
                "order-service",
                "订单服务",
                content("hash-a", sourceRevision, "# 订单服务"),
                new PrincipalId("compiler-user"),
                now
        );

        var draft = store.saveDraft(command);
        var duplicate = store.saveDraft(command);
        assertEquals(draft.page().id(), duplicate.page().id());
        assertEquals(draft.page().version(), duplicate.page().version());

        var review = store.transition(new KnowledgePageStore.TransitionCommand(
                tenant,
                draft.page().id(),
                draft.page().version(),
                KnowledgePageStatus.IN_REVIEW,
                new PrincipalId("reviewer"),
                now.plusSeconds(1)
        ));
        var published = store.transition(new KnowledgePageStore.TransitionCommand(
                tenant,
                draft.page().id(),
                review.page().version(),
                KnowledgePageStatus.PUBLISHED,
                new PrincipalId("reviewer"),
                now.plusSeconds(2)
        ));

        assertEquals(KnowledgePageStatus.PUBLISHED, published.page().status());
        assertEquals(published.page().latestRevisionId(), published.page().activeRevisionId());
        assertEquals(
                published.page().id(),
                store.findPublished(tenant, space, "order-service").orElseThrow().page().id()
        );
        assertEquals(
                1,
                store.findAffectedBySourceRevision(tenant, sourceRevision).size()
        );
        assertTrue(store.findById(new TenantId("tenant-b"), published.page().id()).isEmpty());
    }

    @Test
    void keepsPublishedRevisionActiveWhileNewDraftIsPrepared() {
        TenantId tenant = new TenantId("tenant-a");
        KnowledgeSpaceId space = new KnowledgeSpaceId("engineering");
        Instant now = Instant.parse("2026-08-11T02:00:00Z");
        var first = store.saveDraft(new KnowledgePageStore.DraftCommand(
                tenant, space, "inventory-service", "库存服务",
                content("hash-1", UUID.randomUUID(), "# 库存服务 v1"),
                new PrincipalId("compiler-user"), now
        ));
        var review = store.transition(new KnowledgePageStore.TransitionCommand(
                tenant, first.page().id(), first.page().version(),
                KnowledgePageStatus.IN_REVIEW, new PrincipalId("reviewer"),
                now.plusSeconds(1)
        ));
        var published = store.transition(new KnowledgePageStore.TransitionCommand(
                tenant, first.page().id(), review.page().version(),
                KnowledgePageStatus.PUBLISHED, new PrincipalId("reviewer"),
                now.plusSeconds(2)
        ));
        var nextDraft = store.saveDraft(new KnowledgePageStore.DraftCommand(
                tenant, space, "inventory-service", "库存服务",
                content("hash-2", UUID.randomUUID(), "# 库存服务 v2"),
                new PrincipalId("compiler-user"), now.plusSeconds(3)
        ));

        assertNotEquals(nextDraft.page().latestRevisionId(), nextDraft.page().activeRevisionId());
        assertEquals(
                published.page().activeRevisionId(),
                store.findPublished(tenant, space, "inventory-service")
                        .orElseThrow().revision().id()
        );
    }

    private static KnowledgePageCompiler.CompiledPage content(
            String hash,
            UUID sourceRevision,
            String markdown
    ) {
        return new KnowledgePageCompiler.CompiledPage(
                "摘要",
                markdown,
                List.of(new PageSourceReference(
                        new DocumentId(UUID.randomUUID()),
                        sourceRevision,
                        UUID.randomUUID(),
                        List.of("架构"),
                        "source-" + hash,
                        90
                )),
                hash,
                "wiki-v1",
                "extractive"
        );
    }
}
