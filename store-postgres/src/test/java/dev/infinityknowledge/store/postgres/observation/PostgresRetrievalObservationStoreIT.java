package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationConflictException;
import dev.infinityknowledge.evaluation.observation.RetrievalObservationProcessor;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;
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
        var namedJdbc = new NamedParameterJdbcTemplate(dataSource);
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
        assertEquals(6, jdbc.queryForObject("""
                SELECT count(*)
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ?
                """, Integer.class, TENANT.value(), executionId));
        assertEquals(2, jdbc.queryForObject("""
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
                   AND metric_key = 'retrieval.observation.complete'
                """, Double.class, TENANT.value(), executionId));
        assertTrue(duplicate.duplicate());
        assertTrue(duplicate.facts().isEmpty());

        RetrievalObservation failedTerminal = failedTerminal(executionId, requestId);
        transaction.execute(status -> processor.process(failedTerminal));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*)
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ? AND fact_scope = 'EXECUTION'
                """, Integer.class, TENANT.value(), executionId));
        assertEquals(1.0D, jdbc.queryForObject("""
                SELECT metric_value
                  FROM retrieval_metric_fact
                 WHERE tenant_id = ? AND execution_id = ?
                   AND fact_scope = 'EXECUTION'
                   AND metric_key = 'retrieval.observation.complete'
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
}
