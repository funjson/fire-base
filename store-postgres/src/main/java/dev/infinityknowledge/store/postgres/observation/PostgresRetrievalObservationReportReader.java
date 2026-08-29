package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationCompleteness;
import dev.infinityknowledge.evaluation.observation.ObservationIncompleteReason;
import dev.infinityknowledge.evaluation.observation.VisitedRetrievalConfiguration;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReport;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReportReader;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 从 PostgreSQL 原始事件、execution 投影和指标事实组装安全只读报告。
 *
 * <p>投影的 {@code last_sequence} 是本次读取的水位线。即使下一条事件在三个查询之间
 * 到达，报告也只包含水位线以内的事件和指标，避免把旧完整性结论与新事件混在一起。</p>
 */
public final class PostgresRetrievalObservationReportReader
        implements RetrievalObservationReportReader {

    private final NamedParameterJdbcTemplate jdbc;
    private final RetrievalObservationEventStore eventStore;
    private final ObservationJsonCodec json;

    /** 创建复用现有原始事件读取能力的 PostgreSQL 报告适配器。 */
    public PostgresRetrievalObservationReportReader(NamedParameterJdbcTemplate jdbc) {
        this(
                jdbc,
                new PostgresRetrievalObservationEventStore(jdbc),
                new ObservationJsonCodec()
        );
    }

    PostgresRetrievalObservationReportReader(
            NamedParameterJdbcTemplate jdbc,
            RetrievalObservationEventStore eventStore,
            ObservationJsonCodec json
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public Optional<RetrievalObservationReport> findLatestExecution(
            TenantId tenantId,
            UUID requestId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        MapSqlParameterSource requestParameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("requestId", requestId);
        List<ExecutionProjection> projections = jdbc.query("""
                SELECT execution_id, request_id, purpose, completeness,
                       incomplete_reasons_json::text AS incomplete_reasons_json,
                       missing_sequences_json::text AS missing_sequences_json,
                       visited_configurations_json::text AS visited_configurations_json,
                       event_count, last_sequence
                  FROM retrieval_execution_observation
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                 ORDER BY first_event_at DESC, execution_id DESC
                 LIMIT 1
                """, requestParameters, this::projection);
        if (projections.isEmpty()) {
            return Optional.empty();
        }

        ExecutionProjection projection = projections.getFirst();
        List<RetrievalObservation> observations = observationsAtWatermark(
                tenantId,
                projection
        );
        List<RetrievalObservationReport.EventFact> events = observations.stream()
                .map(RetrievalObservationReport.EventFact::from)
                .toList();
        List<RetrievalObservationReport.MetricFactView> metrics = metricFacts(
                tenantId,
                projection
        ).stream().map(RetrievalObservationReport.MetricFactView::from).toList();

        return Optional.of(new RetrievalObservationReport(
                tenantId,
                projection.requestId(),
                projection.executionId(),
                projection.purpose(),
                projection.completeness(),
                projection.incompleteReasons(),
                projection.missingSequences(),
                projection.visitedConfigurations(),
                events,
                metrics
        ));
    }

    private ExecutionProjection projection(ResultSet row, int number) throws SQLException {
        return new ExecutionProjection(
                row.getObject("execution_id", UUID.class),
                row.getObject("request_id", UUID.class),
                RetrievalObservationPurpose.valueOf(row.getString("purpose")),
                ObservationCompleteness.valueOf(row.getString("completeness")),
                Set.copyOf(json.readIncompleteReasons(
                        row.getString("incomplete_reasons_json")
                )),
                json.readLongs(row.getString("missing_sequences_json")),
                json.readVisitedConfigurations(
                        row.getString("visited_configurations_json")
                ),
                row.getInt("event_count"),
                row.getLong("last_sequence")
        );
    }

    private List<RetrievalObservation> observationsAtWatermark(
            TenantId tenantId,
            ExecutionProjection projection
    ) {
        List<RetrievalObservation> observations = eventStore.events(
                tenantId,
                projection.executionId()
        ).stream()
                .filter(event -> event.sequence() <= projection.lastSequence())
                .sorted(Comparator.comparingLong(RetrievalObservation::sequence))
                .toList();
        if (observations.size() != projection.eventCount()) {
            throw new IllegalStateException(
                    "retrieval observation projection does not match its event watermark"
            );
        }
        for (RetrievalObservation observation : observations) {
            if (!tenantId.equals(observation.tenantId())
                    || !projection.executionId().equals(observation.executionId())
                    || !projection.requestId().equals(observation.requestId())
                    || projection.purpose() != observation.purpose()) {
                throw new IllegalStateException(
                        "retrieval observation event identity does not match its projection"
                );
            }
        }
        return observations;
    }

    private List<MetricFact> metricFacts(
            TenantId tenantId,
            ExecutionProjection projection
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("executionId", projection.executionId())
                .addValue("lastSequence", projection.lastSequence());
        List<MetricFact> facts = jdbc.query("""
                SELECT fact.fact_type, fact.fact_json::text AS fact_json
                  FROM retrieval_metric_fact fact
                  JOIN retrieval_observation_event event
                    ON event.tenant_id = fact.tenant_id
                   AND event.event_id = fact.source_event_id
                 WHERE fact.tenant_id = :tenantId
                   AND fact.execution_id = :executionId
                   AND event.sequence_number <= :lastSequence
                 ORDER BY event.sequence_number, fact.observed_at,
                          fact.metric_key, fact.metric_definition_version,
                          fact.fact_id
                """, parameters, (row, number) -> json.readFact(
                row.getString("fact_type"),
                row.getString("fact_json")
        ));
        for (MetricFact fact : facts) {
            if (!tenantId.equals(fact.tenantId())
                    || !projection.executionId().equals(fact.executionId())) {
                throw new IllegalStateException(
                        "retrieval metric fact identity does not match its projection"
                );
            }
        }
        return List.copyOf(facts);
    }

    private record ExecutionProjection(
            UUID executionId,
            UUID requestId,
            RetrievalObservationPurpose purpose,
            ObservationCompleteness completeness,
            Set<ObservationIncompleteReason> incompleteReasons,
            List<Long> missingSequences,
            List<VisitedRetrievalConfiguration> visitedConfigurations,
            int eventCount,
            long lastSequence
    ) {
        private ExecutionProjection {
            Objects.requireNonNull(executionId, "executionId must not be null");
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(purpose, "purpose must not be null");
            Objects.requireNonNull(completeness, "completeness must not be null");
            incompleteReasons = Set.copyOf(incompleteReasons);
            missingSequences = List.copyOf(missingSequences);
            visitedConfigurations = List.copyOf(visitedConfigurations);
            if (eventCount < 1 || lastSequence < 0L) {
                throw new IllegalStateException("stored retrieval projection counts are invalid");
            }
        }
    }
}
