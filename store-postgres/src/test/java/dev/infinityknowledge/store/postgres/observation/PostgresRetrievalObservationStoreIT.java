package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationConflictException;
import dev.infinityknowledge.evaluation.observation.RetrievalObservationProcessor;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityReader;
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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 PostgreSQL 验证原始事件、执行投影和指标事实的事务幂等语义。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_POSTGRES_TESTS", matches = "true")
class PostgresRetrievalObservationStoreIT {
    // 真实运行时钟通常携带纳秒尾数，用它覆盖 PostgreSQL 微秒精度的往返契约。
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03.123456789Z");
    private static final TenantId TENANT = new TenantId("tenant-observation");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("infinity_knowledge")
                    .withUsername("infinity_knowledge")
                    .withPassword("infinity_knowledge");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static NamedParameterJdbcTemplate namedJdbc;
    private static PostgresRetrievalObservationEventStore eventStore;
    private static PostgresMetricFactStore metricStore;
    private static RetrievalObservationProcessor processor;

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
        namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        eventStore = new PostgresRetrievalObservationEventStore(namedJdbc);
        metricStore = new PostgresMetricFactStore(namedJdbc);
        processor = new RetrievalObservationProcessor(
                eventStore,
                new PostgresRetrievalExecutionProjectionStore(namedJdbc),
                metricStore
        );
        var databaseTime = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Observation Tenant', 'ACTIVE', ?, ?)
                """, TENANT.value(), databaseTime, databaseTime);
    }

    @Test
    void aggregatesOnlineRecoveryByActualVisitAndExcludesTestPlaza() {
        TenantId tenant = new TenantId("tenant-online-reader");
        KnowledgeSpaceId spaceA = new KnowledgeSpaceId("space-a");
        KnowledgeSpaceId spaceB = new KnowledgeSpaceId("space-b");
        Instant base = NOW.plusSeconds(3600);
        var databaseTime = base.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Online Reader Tenant', 'ACTIVE', ?, ?)
                """, tenant.value(), databaseTime, databaseTime);
        var localProcessor = new RetrievalObservationProcessor(
                eventStore,
                new PostgresRetrievalExecutionProjectionStore(namedJdbc),
                metricStore
        );
        processObservedExecution(
                localProcessor,
                tenant,
                RetrievalObservationPurpose.ONLINE,
                spaceA,
                spaceB,
                base,
                "d".repeat(64),
                "index-online"
        );
        processObservedExecution(
                localProcessor,
                tenant,
                RetrievalObservationPurpose.TEST_PLAZA,
                spaceA,
                spaceB,
                base.plusSeconds(10),
                "e".repeat(64),
                "index-test"
        );
        var reader = new PostgresOnlineRetrievalObservabilityReader(namedJdbc);
        var query = new OnlineRetrievalObservabilityQuery(
                tenant,
                Set.of(spaceA, spaceB),
                Optional.of(spaceA),
                base.minusSeconds(1),
                base.plusSeconds(30),
                base.plusSeconds(30),
                Optional.of("d".repeat(64)),
                Optional.of("index-online")
        );

        var overview = reader.overview(query);
        // 数据索引过滤会把诊断约束到实际使用该索引的召回轮次；这里去掉索引过滤，
        // 用于验证同一 Space/配置下首轮和优化轮的完整 Coverage 轨迹。
        var stages = reader.stages(new OnlineRetrievalObservabilityQuery(
                tenant,
                Set.of(spaceA, spaceB),
                Optional.of(spaceA),
                base.minusSeconds(1),
                base.plusSeconds(30),
                base.plusSeconds(30),
                Optional.of("d".repeat(64)),
                Optional.empty()
        ));
        var page = reader.executions(
                query,
                new OnlineRetrievalObservabilityReader.ExecutionPageRequest(
                        0, 20, Optional.empty(), Optional.empty()
                )
        );

        assertEquals(1L, overview.requestCount());
        assertEquals(1.0D, overview.technicalSuccessRate().value());
        assertEquals(0.0D, overview.firstCoverageSufficientRate().value());
        assertEquals(1.0D, overview.finalCoverageSufficientRate().value());
        assertEquals(1.0D, overview.coverageRecoveryRate().value());
        assertEquals(1L, overview.endToEndLatencyMillis().sampleCount());
        assertEquals(6000.0D, overview.endToEndLatencyMillis().p50());
        assertEquals(2L, stages.coverage().checkCount());
        assertEquals(0.55D, stages.coverage().averageScore(), 0.000001D);
        assertEquals(1, page.items().size());
        assertEquals("SUFFICIENT", page.items().getFirst().terminalStatus());

        var routerCandidateOnly = reader.overview(new OnlineRetrievalObservabilityQuery(
                tenant,
                Set.of(spaceA, spaceB),
                Optional.of(spaceB),
                base.minusSeconds(1),
                base.plusSeconds(30),
                base.plusSeconds(30),
                Optional.empty(),
                Optional.empty()
        ));
        assertEquals(0L, routerCandidateOnly.requestCount());

        var unauthorizedRouterReference = reader.overview(
                new OnlineRetrievalObservabilityQuery(
                        tenant,
                        Set.of(spaceA),
                        Optional.of(spaceA),
                        base.minusSeconds(1),
                        base.plusSeconds(30),
                        base.plusSeconds(30),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertEquals(0L, unauthorizedRouterReference.requestCount());
    }

    @Test
    void includesConfigurationFailureInTheActualSpaceCohort() {
        TenantId tenant = new TenantId("tenant-config-failure-reader");
        KnowledgeSpaceId spaceA = new KnowledgeSpaceId("space-failure-a");
        KnowledgeSpaceId spaceB = new KnowledgeSpaceId("space-router-candidate-b");
        Instant base = NOW.plusSeconds(7200);
        var databaseTime = base.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO knowledge_tenant
                    (id, display_name, status, created_at, updated_at)
                VALUES (?, 'Config Failure Reader Tenant', 'ACTIVE', ?, ?)
                """, tenant.value(), databaseTime, databaseTime);
        var localProcessor = new RetrievalObservationProcessor(
                eventStore,
                new PostgresRetrievalExecutionProjectionStore(namedJdbc),
                metricStore
        );
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        List<RetrievalObservation> events = List.of(
                observed(
                        executionId, requestId, 0L, RetrievalObservationPurpose.ONLINE,
                        tenant, Set.of(), 0, 0, RetrievalObservationStatus.STARTED,
                        "NONE", RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        base, base,
                        new RetrievalObservationPayload.ExecutionStarted(
                                new RetrievalObservationPayload.TextFingerprint(
                                        "HMAC_SHA256", "key-v1", "f".repeat(64)
                                ),
                                8,
                                0
                        )
                ),
                observed(
                        executionId, requestId, 1L, RetrievalObservationPurpose.ONLINE,
                        tenant, Set.of(spaceA, spaceB), 0, 0,
                        RetrievalObservationStatus.SUCCEEDED, "NONE",
                        RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        base.plusSeconds(1), base.plusSeconds(1),
                        new RetrievalObservationPayload.SpaceRoutingCompleted(
                                2,
                                List.of(
                                        new RetrievalObservationPayload.RankedSpace(spaceA, 1),
                                        new RetrievalObservationPayload.RankedSpace(spaceB, 2)
                                )
                        )
                ),
                observed(
                        executionId, requestId, 2L, RetrievalObservationPurpose.ONLINE,
                        tenant, Set.of(spaceA), 0, 0, RetrievalObservationStatus.FAILED,
                        "CONFIGURATION_RESOLUTION_FAILED",
                        RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        base.plusSeconds(2), base.plusSeconds(2),
                        new RetrievalObservationPayload.StageFailed(
                                "CONFIGURATION_RESOLVED", 1
                        )
                ),
                observed(
                        executionId, requestId, 3L, RetrievalObservationPurpose.ONLINE,
                        tenant, Set.of(spaceA), 0, 0, RetrievalObservationStatus.FAILED,
                        "CONFIGURATION_RESOLUTION_FAILED",
                        RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        base, base.plusSeconds(3),
                        new RetrievalObservationPayload.ExecutionTerminal(
                                RetrievalTerminalStatus.TECHNICAL_FAILED,
                                RetrievalStopReason.TECHNICAL_FAILURE,
                                0, 0, false, List.of()
                        )
                )
        );
        events.forEach(event -> transaction.execute(status -> localProcessor.process(event)));
        var reader = new PostgresOnlineRetrievalObservabilityReader(namedJdbc);
        var query = new OnlineRetrievalObservabilityQuery(
                tenant,
                Set.of(spaceA, spaceB),
                Optional.of(spaceA),
                base.minusSeconds(1),
                base.plusSeconds(30),
                base.plusSeconds(30),
                Optional.of(RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT),
                Optional.empty()
        );

        var overview = reader.overview(query);
        var page = reader.executions(
                query,
                new OnlineRetrievalObservabilityReader.ExecutionPageRequest(
                        0, 20, Optional.empty(), Optional.empty()
                )
        );

        assertEquals(1L, overview.requestCount());
        assertEquals(
                1L,
                overview.configFingerprints().stream()
                        .filter(value -> RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT.equals(
                                value.value()
                        ))
                        .findFirst()
                        .orElseThrow()
                        .count()
        );
        assertEquals(List.of(spaceA.value()), page.items().getFirst().spaceIds());
        assertEquals(0L, reader.overview(new OnlineRetrievalObservabilityQuery(
                tenant,
                Set.of(spaceA, spaceB),
                Optional.of(spaceB),
                base.minusSeconds(1),
                base.plusSeconds(30),
                base.plusSeconds(30),
                Optional.empty(),
                Optional.empty()
        )).requestCount());
    }

    @Test
    void storesAndReplaysWithoutSilentlyAcceptingConflicts() {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        RetrievalObservation observation = started(
                eventId, executionId, requestId, "a"
        );

        var first = transaction.execute(status -> processor.process(observation));
        var duplicate = transaction.execute(status -> processor.process(observation));

        assertEquals(observation, eventStore.events(TENANT, executionId).getFirst());
        assertEquals(1, jdbc.queryForObject("""
                SELECT event_count
                  FROM retrieval_execution_observation
                 WHERE tenant_id = ? AND execution_id = ?
                """, Integer.class, TENANT.value(), executionId));
        assertEquals(10, jdbc.queryForObject("""
                SELECT count(*)
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ?
                """, Integer.class, TENANT.value(), executionId));
        assertEquals(6, jdbc.queryForObject("""
                SELECT count(*)
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ? AND fact_scope = 'EXECUTION'
                """, Integer.class, TENANT.value(), executionId));
        assertEquals(1.0D, jdbc.queryForObject("""
                SELECT metric_value
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ?
                   AND fact_scope = 'EXECUTION' AND metric_key = 'retrieval.request.count'
                """, Double.class, TENANT.value(), executionId));
        assertEquals(0.0D, jdbc.queryForObject("""
                SELECT metric_value
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ?
                   AND fact_scope = 'EXECUTION'
                   AND metric_key = 'retrieval.observation.complete.rate'
                   AND aggregation = 'RATIO_NUMERATOR'
                """, Double.class, TENANT.value(), executionId));
        assertEquals(4, jdbc.queryForObject("""
                SELECT count(*)
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ?
                   AND fact_scope = 'EVENT'
                   AND technical_status = 'STARTED'
                   AND stage = 'EXECUTION_STARTED'
                   AND visit_index = 0
                """, Integer.class, TENANT.value(), executionId));
        assertTrue(duplicate.duplicate());
        assertTrue(duplicate.facts().isEmpty());

        RetrievalObservation failedTerminal = failedTerminal(executionId, requestId);
        transaction.execute(status -> processor.process(failedTerminal));
        assertEquals("TECHNICAL_FAILED", jdbc.queryForObject("""
                SELECT terminal_status
                  FROM retrieval_execution_observation
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, TENANT.value(), executionId));
        assertEquals("TECHNICAL_FAILURE", jdbc.queryForObject("""
                SELECT terminal_reason_code
                  FROM retrieval_execution_observation
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, TENANT.value(), executionId));
        assertEquals("FAILED", jdbc.queryForObject("""
                SELECT technical_status
                  FROM retrieval_execution_observation
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, TENANT.value(), executionId));
        assertEquals(14, jdbc.queryForObject("""
                SELECT count(*)
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ? AND fact_scope = 'EXECUTION'
                """, Integer.class, TENANT.value(), executionId));
        assertEquals(1.0D, jdbc.queryForObject("""
                SELECT metric_value
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ?
                   AND fact_scope = 'EXECUTION'
                   AND metric_key = 'retrieval.observation.complete.rate'
                   AND aggregation = 'RATIO_NUMERATOR'
                """, Double.class, TENANT.value(), executionId));

        RetrievalObservation changed = started(eventId, executionId, requestId, "b");
        assertThrows(ObservationConflictException.class, () -> transaction.execute(
                status -> processor.process(changed)
        ));

        MetricFact.Runtime existing = first.facts().getFirst();
        MetricFact.Runtime conflictingFact = new MetricFact.Runtime(
                existing.sourceEventId(),
                existing.executionId(),
                existing.tenantId(),
                existing.metricKey(),
                existing.metricDefinitionVersion(),
                existing.aggregation(),
                existing.value() + 1.0D,
                existing.dimensions(),
                existing.observedAt()
        );
        assertThrows(ObservationConflictException.class, () -> transaction.execute(
                status -> metricStore.appendAll(List.of(conflictingFact))
        ));

        MetricDimensions terminalDimensions = MetricDimensions.builder()
                .config(RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT)
                .terminalStatus(RetrievalTerminalStatus.CHECK_FAILED)
                .stopReason(RetrievalStopReason.COVERAGE_CHECK_FAILED)
                .unobservedTechnicalStatus()
                .purpose(RetrievalObservationPurpose.ONLINE)
                .timeSlice(NOW, MetricDimensions.TimeSliceGranularity.HOUR)
                .build();
        MetricFact.Runtime terminalFact = new MetricFact.Runtime(
                observation.eventId(),
                observation.executionId(),
                observation.tenantId(),
                "retrieval.test.stop_reason",
                2,
                MetricFact.Aggregation.DISTRIBUTION,
                0.0D,
                terminalDimensions,
                NOW
        );
        transaction.executeWithoutResult(
                status -> metricStore.appendAll(List.of(terminalFact))
        );
        assertEquals("COVERAGE_CHECK_FAILED", jdbc.queryForObject("""
                SELECT stop_reason
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ? AND metric_key = ?
                """, String.class, TENANT.value(), executionId, terminalFact.metricKey()));
    }

    @Test
    void persistsStageFailureIntroducedByLatestObservationSchema() {
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var executionStarted = started(
                UUID.randomUUID(), executionId, requestId, "c"
        );
        var payload = new RetrievalObservationPayload.StageFailed(
                "RETRIEVAL_BRANCH",
                1
        );
        var observation = new RetrievalObservation(
                UUID.randomUUID(), executionId, requestId, 1L,
                RetrievalObservationPurpose.ONLINE, TENANT, Set.of(),
                0, 0, payload.stage(), RetrievalObservationStatus.FAILED,
                "RETRIEVAL_BRANCH_FAILED",
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                NOW, NOW, RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
        );

        transaction.execute(status -> eventStore.append(executionStarted));
        var outcome = transaction.execute(status -> eventStore.append(observation));

        assertEquals(RetrievalObservationEventStore.AppendOutcome.APPENDED, outcome);
        assertEquals(
                List.of(executionStarted, observation),
                eventStore.events(TENANT, executionId)
        );
    }

    private static RetrievalObservation started(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            String fingerprintDigit
    ) {
        var payload = new RetrievalObservationPayload.ExecutionStarted(
                new RetrievalObservationPayload.TextFingerprint(
                        "HMAC_SHA256", "key-v1", fingerprintDigit.repeat(64)
                ),
                8,
                0
        );
        return new RetrievalObservation(
                eventId, executionId, requestId, 0L,
                RetrievalObservationPurpose.ONLINE, TENANT, Set.of(),
                0, 0, payload.stage(), RetrievalObservationStatus.STARTED, "NONE",
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                NOW, NOW, RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
        );
    }

    private static RetrievalObservation failedTerminal(UUID executionId, UUID requestId) {
        var payload = new RetrievalObservationPayload.ExecutionTerminal(
                RetrievalTerminalStatus.TECHNICAL_FAILED,
                RetrievalStopReason.TECHNICAL_FAILURE,
                0,
                0,
                false,
                List.of()
        );
        return new RetrievalObservation(
                UUID.randomUUID(), executionId, requestId, 1L,
                RetrievalObservationPurpose.ONLINE, TENANT, Set.of(),
                0, 0, payload.stage(), RetrievalObservationStatus.FAILED,
                "TECHNICAL_FAILURE",
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                NOW, NOW, RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
        );
    }

    private static void processObservedExecution(
            RetrievalObservationProcessor localProcessor,
            TenantId tenant,
            RetrievalObservationPurpose purpose,
            KnowledgeSpaceId visitedSpace,
            KnowledgeSpaceId routerCandidateOnly,
            Instant base,
            String config,
            String dataIndexVersion
    ) {
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var component = new RetrievalObservationPayload.ComponentVersion(
                "retriever", "runtime", "hybrid", "v1"
        );
        var fingerprint = new RetrievalObservationPayload.TextFingerprint(
                "HMAC_SHA256", "key-v1", "f".repeat(64)
        );
        List<RetrievalObservation> observations = List.of(
                observed(
                        executionId,
                        requestId,
                        0L,
                        purpose,
                        tenant,
                        Set.of(),
                        0,
                        0,
                        RetrievalObservationStatus.STARTED,
                        "NONE",
                        RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        base,
                        base,
                        new RetrievalObservationPayload.ExecutionStarted(fingerprint, 8, 2)
                ),
                observed(
                        executionId, requestId, 1L, purpose, tenant,
                        Set.of(visitedSpace, routerCandidateOnly), 0, 0,
                        RetrievalObservationStatus.SUCCEEDED, "NONE",
                        RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                        base.plusSeconds(1), base.plusSeconds(1),
                        new RetrievalObservationPayload.SpaceRoutingCompleted(
                                2,
                                List.of(
                                        new RetrievalObservationPayload.RankedSpace(
                                                visitedSpace, 1
                                        ),
                                        new RetrievalObservationPayload.RankedSpace(
                                                routerCandidateOnly, 2
                                        )
                                )
                        )
                ),
                observed(
                        executionId, requestId, 2L, purpose, tenant,
                        Set.of(visitedSpace), 0, 0,
                        RetrievalObservationStatus.SUCCEEDED, "NONE", config,
                        base.plusSeconds(2), base.plusSeconds(2),
                        new RetrievalObservationPayload.ConfigurationResolved(
                                visitedSpace,
                                1L,
                                List.of(component),
                                List.of(new RetrievalObservationPayload.DataIndexVersion(
                                        visitedSpace,
                                        UUID.randomUUID(),
                                        dataIndexVersion
                                ))
                        )
                ),
                observed(
                        executionId, requestId, 3L, purpose, tenant,
                        Set.of(visitedSpace), 0, 0,
                        RetrievalObservationStatus.SUCCEEDED, "NONE", config,
                        base.plusSeconds(3), base.plusSeconds(3),
                        new RetrievalObservationPayload.RetrievalBranchCompleted(
                                "branch-1", "BASELINE", "q0", fingerprint,
                                RetrievalChannel.VECTOR, component, dataIndexVersion,
                                8, 8, List.of()
                        )
                ),
                observed(
                        executionId, requestId, 4L, purpose, tenant,
                        Set.of(visitedSpace), 0, 0,
                        RetrievalObservationStatus.SUCCEEDED, "NONE", config,
                        base.plusSeconds(4), base.plusSeconds(4),
                        coverage(component, 0.3D,
                                RetrievalObservationPayload.CoverageTerminalStatus.CONTINUE)
                ),
                observed(
                        executionId, requestId, 5L, purpose, tenant,
                        Set.of(visitedSpace), 0, 1,
                        RetrievalObservationStatus.SUCCEEDED, "NONE", config,
                        base.plusSeconds(5), base.plusSeconds(5),
                        coverage(component, 0.8D,
                                RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT)
                ),
                observed(
                        executionId, requestId, 6L, purpose, tenant,
                        Set.of(visitedSpace), 0, 1,
                        RetrievalObservationStatus.SUCCEEDED, "NONE", config,
                        base, base.plusSeconds(6),
                        new RetrievalObservationPayload.ExecutionTerminal(
                                RetrievalTerminalStatus.SUFFICIENT,
                                RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED,
                                2, 1, false, List.of()
                        )
                )
        );
        observations.forEach(observation -> transaction.execute(
                status -> localProcessor.process(observation)
        ));
    }

    private static RetrievalObservationPayload.CoverageCheckCompleted coverage(
            RetrievalObservationPayload.ComponentVersion component,
            double score,
            RetrievalObservationPayload.CoverageTerminalStatus status
    ) {
        return new RetrievalObservationPayload.CoverageCheckCompleted(
                component,
                1,
                1,
                1,
                RetrievalObservationPayload.OptionalCount.absent(),
                RetrievalObservationPayload.OptionalScore.of(score),
                RetrievalObservationPayload.OptionalScore.of(0.7D),
                status,
                status == RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT
                        ? "SUFFICIENCY_THRESHOLD_REACHED" : "CONTINUE",
                RetrievalObservationPayload.UsageCount.unmeasured(1)
        );
    }

    private static RetrievalObservation observed(
            UUID executionId,
            UUID requestId,
            long sequence,
            RetrievalObservationPurpose purpose,
            TenantId tenant,
            Set<KnowledgeSpaceId> spaces,
            int visitIndex,
            int attemptIndex,
            RetrievalObservationStatus status,
            String reasonCode,
            String config,
            Instant startedAt,
            Instant completedAt,
            RetrievalObservationPayload payload
    ) {
        return new RetrievalObservation(
                UUID.randomUUID(), executionId, requestId, sequence, purpose, tenant,
                spaces, visitIndex, attemptIndex, payload.stage(), status, reasonCode,
                config, startedAt, completedAt, RetrievalObservation.CURRENT_SCHEMA_VERSION,
                payload
        );
    }
}
