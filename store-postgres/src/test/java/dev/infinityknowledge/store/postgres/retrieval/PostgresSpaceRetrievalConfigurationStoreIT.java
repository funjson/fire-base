package dev.infinityknowledge.store.postgres.retrieval;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 PostgreSQL 验证不可变检索配置修订、当前指针和租户隔离。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresSpaceRetrievalConfigurationStoreIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static PostgresKnowledgeGovernanceStore governance;
    private static PostgresSpaceRetrievalConfigurationStore store;
    private static JdbcTemplate jdbc;

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
        var namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        var transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        governance = new PostgresKnowledgeGovernanceStore(namedJdbc);
        store = new PostgresSpaceRetrievalConfigurationStore(namedJdbc, transaction);
    }

    @Test
    void appendsImmutableRevisionsAndAtomicallySwitchesCurrentPointer() {
        PrincipalContext admin = admin("tenant-retrieval-version", "admin-version");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering-version");
        createSpace(admin, spaceId);
        SpaceRetrievalConfiguration first = revision(admin, spaceId, 1L, 40);
        SpaceRetrievalConfiguration second = revision(admin, spaceId, 2L, 60);

        assertEquals(
                SpaceRetrievalConfigurationStore.ActivationOutcome.ACTIVATED,
                store.appendAndActivate(first, 0L)
        );
        assertEquals(
                SpaceRetrievalConfigurationStore.ActivationOutcome.ACTIVATED,
                store.appendAndActivate(second, 1L)
        );

        assertEquals(second, store.findCurrent(admin.tenantId(), spaceId).orElseThrow());
        assertEquals(first, store.findRevision(
                admin.tenantId(), spaceId, 1L
        ).orElseThrow());
        assertEquals(
                List.of(2L, 1L),
                store.history(admin.tenantId(), spaceId, 20).stream()
                        .map(SpaceRetrievalConfiguration::revision)
                        .toList()
        );
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void supportsIdempotentRetryAndRejectsDifferentContentAtSameRevision() {
        PrincipalContext admin = admin("tenant-retrieval-retry", "admin-retry");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering-retry");
        createSpace(admin, spaceId);
        SpaceRetrievalConfiguration first = revision(admin, spaceId, 1L, 40);

        assertEquals(
                SpaceRetrievalConfigurationStore.ActivationOutcome.ACTIVATED,
                store.appendAndActivate(first, 0L)
        );
        assertEquals(
                SpaceRetrievalConfigurationStore.ActivationOutcome.ALREADY_ACTIVE,
                store.appendAndActivate(first, 0L)
        );
        assertEquals(
                SpaceRetrievalConfigurationStore.ActivationOutcome.REVISION_CONFLICT,
                store.appendAndActivate(revision(admin, spaceId, 1L, 80), 0L)
        );
        assertEquals(first, store.findCurrent(admin.tenantId(), spaceId).orElseThrow());
    }

    @Test
    void neverReturnsAnotherTenantsRevision() {
        PrincipalContext tenantA = admin("tenant-retrieval-a", "admin-a");
        PrincipalContext tenantB = admin("tenant-retrieval-b", "admin-b");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("shared-retrieval-space");
        createSpace(tenantA, spaceId);
        createSpace(tenantB, spaceId);
        store.appendAndActivate(revision(tenantA, spaceId, 1L, 40), 0L);

        assertTrue(store.findCurrent(tenantB.tenantId(), spaceId).isEmpty());
        assertTrue(store.history(tenantB.tenantId(), spaceId, 20).isEmpty());
    }

    @Test
    void databaseRejectsMutationOfHistoricalVersion() {
        PrincipalContext admin = admin("tenant-retrieval-immutable", "admin-immutable");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering-immutable");
        createSpace(admin, spaceId);
        store.appendAndActivate(revision(admin, spaceId, 1L, 40), 0L);

        assertThrows(RuntimeException.class, () -> jdbc.update("""
                UPDATE space_retrieval_configuration_version
                   SET fingerprint = ?
                 WHERE tenant_id = ? AND space_id = ? AND revision = 1
                """, "0".repeat(64), admin.tenantId().value(), spaceId.value()));
    }

    @Test
    void concurrentWritersCannotCreateTwoCurrentFirstRevisions() throws Exception {
        PrincipalContext admin = admin("tenant-retrieval-concurrent", "admin-concurrent");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering-concurrent");
        createSpace(admin, spaceId);
        SpaceRetrievalConfiguration first = revision(admin, spaceId, 1L, 40);
        SpaceRetrievalConfiguration second = revision(admin, spaceId, 1L, 80);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstResult = executor.submit(() -> {
                start.await();
                return store.appendAndActivate(first, 0L);
            });
            var secondResult = executor.submit(() -> {
                start.await();
                return store.appendAndActivate(second, 0L);
            });
            start.countDown();

            Set<SpaceRetrievalConfigurationStore.ActivationOutcome> outcomes = Set.of(
                    firstResult.get(15, TimeUnit.SECONDS),
                    secondResult.get(15, TimeUnit.SECONDS)
            );
            assertEquals(
                    Set.of(
                            SpaceRetrievalConfigurationStore.ActivationOutcome.ACTIVATED,
                            SpaceRetrievalConfigurationStore.ActivationOutcome.REVISION_CONFLICT
                    ),
                    outcomes
            );
            assertEquals(1, store.history(admin.tenantId(), spaceId, 20).size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void createSpace(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId
    ) {
        governance.createSpace(
                principal,
                spaceId,
                "Engineering",
                "Engineering retrieval configuration test space",
                Instant.parse("2026-08-26T00:00:00Z")
        );
    }

    private static SpaceRetrievalConfiguration revision(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            long revision,
            int topK
    ) {
        RetrievalConfiguration configuration = new RetrievalConfiguration(
                new RetrievalConfiguration.FirstRound(
                        true,
                        "engineering-terms-v1",
                        4
                ),
                new RetrievalConfiguration.Branches(
                        2,
                        4,
                        60,
                        Map.of(
                                RetrievalChannel.KEYWORD,
                                new RetrievalConfiguration.Branch(true, topK, 1.2D),
                                RetrievalChannel.VECTOR,
                                new RetrievalConfiguration.Branch(true, topK, 1.0D),
                                RetrievalChannel.GRAPH,
                                new RetrievalConfiguration.Branch(false, 10, 1.0D),
                                RetrievalChannel.PAGE,
                                new RetrievalConfiguration.Branch(false, 10, 1.0D)
                        )
                ),
                new RetrievalConfiguration.Reranker(
                        true,
                        "zhipu",
                        "rerank-v1",
                        40,
                        5
                ),
                new RetrievalConfiguration.Coverage(
                        true,
                        "zhipu",
                        "glm-coverage-v1",
                        "coverage-prompt-v1",
                        5,
                        0.8D
                ),
                3,
                Map.of(
                        RetrievalConfiguration.ChainNode.GAP_QUERY, true,
                        RetrievalConfiguration.ChainNode.PRF, false,
                        RetrievalConfiguration.ChainNode.RELAX_CONSTRAINTS, false,
                        RetrievalConfiguration.ChainNode.NARROW_CONSTRAINTS, false,
                        RetrievalConfiguration.ChainNode.STEP_BACK, true,
                        RetrievalConfiguration.ChainNode.HYDE, false,
                        RetrievalConfiguration.ChainNode.NEXT_SPACE, false
                ),
                new RetrievalConfiguration.CrossSpace(false, 1)
        );
        return SpaceRetrievalConfiguration.create(
                principal.tenantId(),
                spaceId,
                revision,
                configuration,
                principal.principalId(),
                Instant.parse("2026-08-26T00:00:00Z").plusSeconds(revision)
        );
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
