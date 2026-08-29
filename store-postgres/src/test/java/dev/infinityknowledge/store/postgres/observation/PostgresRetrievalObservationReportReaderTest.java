package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationIncompleteReason;
import dev.infinityknowledge.evaluation.observation.VisitedRetrievalConfiguration;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 PostgreSQL 报告读取始终绑定租户，并按投影水位线裁剪并发新事件。 */
class PostgresRetrievalObservationReportReaderTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");
    private static final String CONFIG = "a".repeat(64);

    @Test
    @SuppressWarnings("unchecked")
    void returnsSafeOrderedFactsAtTheProjectionWatermark() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        RetrievalObservation started = started(executionId, requestId);
        RetrievalObservation configured = configured(executionId, requestId);
        RetrievalObservation newer = newer(executionId, requestId);
        MetricFact.Runtime metric = metric(configured, executionId);
        ObservationJsonCodec codec = new ObservationJsonCodec();

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RetrievalObservationEventStore events = mock(RetrievalObservationEventStore.class);
        when(events.events(TENANT, executionId)).thenReturn(
                List.of(newer, configured, started)
        );
        List<String> statements = new ArrayList<>();
        List<SqlParameterSource> parameters = new ArrayList<>();
        ResultSet projectionRow = projectionRow(
                codec,
                executionId,
                requestId,
                configured
        );
        ResultSet metricRow = mock(ResultSet.class);
        when(metricRow.getString("fact_type")).thenReturn("RUNTIME");
        when(metricRow.getString("fact_json")).thenReturn(codec.json(metric));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            statements.add(sql);
            parameters.add(invocation.getArgument(1));
            RowMapper<Object> mapper = invocation.getArgument(2);
            return sql.contains("retrieval_execution_observation")
                    ? List.of(mapper.mapRow(projectionRow, 0))
                    : List.of(mapper.mapRow(metricRow, 0));
        }).when(jdbc).query(
                anyString(),
                any(SqlParameterSource.class),
                any(RowMapper.class)
        );

        var report = new PostgresRetrievalObservationReportReader(
                jdbc,
                events,
                codec
        ).findLatestExecution(TENANT, requestId).orElseThrow();

        assertEquals(List.of(0L, 1L), report.events().stream()
                .map(event -> event.sequence())
                .toList());
        assertEquals(1, report.events().get(1).inputCount());
        assertEquals(1, report.events().get(1).outputCount());
        assertEquals(List.of("retrieval.request.count"), report.metricFacts().stream()
                .map(fact -> fact.metricKey())
                .toList());
        assertEquals(Set.of(SPACE), report.involvedSpaceIds());
        assertTrue(statements.getFirst().contains(
                "WHERE tenant_id = :tenantId AND request_id = :requestId"
        ));
        assertTrue(statements.getFirst().contains("ORDER BY first_event_at DESC"));
        assertEquals(TENANT.value(), parameters.getFirst().getValue("tenantId"));
        assertEquals(requestId, parameters.getFirst().getValue("requestId"));
        assertTrue(statements.get(1).contains("fact.tenant_id = :tenantId"));
        assertTrue(statements.get(1).contains("event.sequence_number <= :lastSequence"));
        verify(events).events(TENANT, executionId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyWithoutReadingEventsWhenTenantRequestDoesNotExist() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RetrievalObservationEventStore events = mock(RetrievalObservationEventStore.class);
        doAnswer(invocation -> List.of()).when(jdbc).query(
                anyString(),
                any(SqlParameterSource.class),
                any(RowMapper.class)
        );

        assertTrue(new PostgresRetrievalObservationReportReader(
                jdbc,
                events,
                new ObservationJsonCodec()
        ).findLatestExecution(TENANT, UUID.randomUUID()).isEmpty());

        verifyNoInteractions(events);
    }

    private static ResultSet projectionRow(
            ObservationJsonCodec codec,
            UUID executionId,
            UUID requestId,
            RetrievalObservation configured
    ) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("execution_id", UUID.class)).thenReturn(executionId);
        when(row.getObject("request_id", UUID.class)).thenReturn(requestId);
        when(row.getString("purpose")).thenReturn("ONLINE");
        when(row.getString("completeness")).thenReturn("INCOMPLETE");
        when(row.getString("incomplete_reasons_json")).thenReturn(codec.json(
                List.of(ObservationIncompleteReason.TERMINAL_MISSING)
        ));
        when(row.getString("missing_sequences_json")).thenReturn("[]");
        when(row.getString("visited_configurations_json")).thenReturn(codec.json(List.of(
                new VisitedRetrievalConfiguration(0, SPACE, 3L, CONFIG)
        )));
        when(row.getInt("event_count")).thenReturn(2);
        when(row.getLong("last_sequence")).thenReturn(configured.sequence());
        return row;
    }

    private static RetrievalObservation started(UUID executionId, UUID requestId) {
        var payload = new RetrievalObservationPayload.ExecutionStarted(
                new RetrievalObservationPayload.TextFingerprint(
                        "HMAC_SHA256",
                        "key-v1",
                        "b".repeat(64)
                ),
                8,
                1
        );
        return observation(
                UUID.randomUUID(),
                executionId,
                requestId,
                0L,
                Set.of(),
                RetrievalObservationStatus.STARTED,
                "NONE",
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                payload
        );
    }

    private static RetrievalObservation configured(UUID executionId, UUID requestId) {
        var payload = new RetrievalObservationPayload.ConfigurationResolved(
                SPACE,
                3L,
                List.of(new RetrievalObservationPayload.ComponentVersion(
                        "retriever",
                        "built-in",
                        "none",
                        "v1"
                )),
                List.of()
        );
        return observation(
                UUID.randomUUID(),
                executionId,
                requestId,
                1L,
                Set.of(SPACE),
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                CONFIG,
                payload
        );
    }

    private static RetrievalObservation newer(UUID executionId, UUID requestId) {
        var payload = new RetrievalObservationPayload.QueryAnalysisCompleted(
                new RetrievalObservationPayload.TextFingerprint(
                        "HMAC_SHA256",
                        "key-v1",
                        "c".repeat(64)
                ),
                Set.of(RetrievalChannel.KEYWORD),
                8,
                new RetrievalObservationPayload.ComponentVersion(
                        "analyzer",
                        "built-in",
                        "none",
                        "v1"
                )
        );
        return observation(
                UUID.randomUUID(),
                executionId,
                requestId,
                2L,
                Set.of(SPACE),
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                CONFIG,
                payload
        );
    }

    private static RetrievalObservation observation(
            UUID eventId,
            UUID executionId,
            UUID requestId,
            long sequence,
            Set<KnowledgeSpaceId> spaces,
            RetrievalObservationStatus status,
            String reasonCode,
            String configFingerprint,
            RetrievalObservationPayload payload
    ) {
        return new RetrievalObservation(
                eventId,
                executionId,
                requestId,
                sequence,
                RetrievalObservationPurpose.ONLINE,
                TENANT,
                spaces,
                0,
                0,
                payload.stage(),
                status,
                reasonCode,
                configFingerprint,
                NOW,
                NOW.plusMillis(sequence),
                RetrievalObservation.CURRENT_SCHEMA_VERSION,
                payload
        );
    }

    private static MetricFact.Runtime metric(
            RetrievalObservation source,
            UUID executionId
    ) {
        return new MetricFact.Runtime(
                source.eventId(),
                executionId,
                TENANT,
                "retrieval.request.count",
                1,
                MetricFact.Aggregation.COUNT,
                1.0D,
                MetricDimensions.builder()
                        .space(SPACE)
                        .config(CONFIG)
                        .attempt(0)
                        .status(RetrievalObservationStatus.SUCCEEDED)
                        .purpose(RetrievalObservationPurpose.ONLINE)
                        .timeSlice(NOW, MetricDimensions.TimeSliceGranularity.HOUR)
                        .build(),
                NOW
        );
    }
}
