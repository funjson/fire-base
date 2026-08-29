package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.evaluation.observation.RetrievalExecutionObservation;
import dev.infinityknowledge.evaluation.observation.store.RetrievalExecutionProjectionStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Objects;

/** PostgreSQL execution 观测投影；原始事件仍是唯一事实源。 */
public final class PostgresRetrievalExecutionProjectionStore
        implements RetrievalExecutionProjectionStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObservationJsonCodec json;

    /** 创建 execution 投影存储。 */
    public PostgresRetrievalExecutionProjectionStore(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new ObservationJsonCodec());
    }

    PostgresRetrievalExecutionProjectionStore(
            NamedParameterJdbcTemplate jdbc,
            ObservationJsonCodec json
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public void upsert(RetrievalExecutionObservation execution) {
        Objects.requireNonNull(execution, "execution must not be null");
        RetrievalObservation first = execution.events().getFirst();
        RetrievalObservation last = execution.events().getLast();
        var terminal = execution.terminalEvent();
        var firstEventAt = execution.events().stream()
                .map(RetrievalObservation::startedAt)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        var lastEventAt = execution.events().stream()
                .map(RetrievalObservation::completedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", execution.tenantId().value())
                .addValue("executionId", execution.executionId())
                .addValue("requestId", execution.requestId())
                .addValue("purpose", execution.purpose().name())
                .addValue("completeness", execution.completeness().name())
                .addValue(
                        "incompleteReasonsJson",
                        json.sortedIncompleteReasons(execution.incompleteReasons())
                )
                .addValue("missingSequencesJson", json.json(execution.missingSequences()))
                .addValue(
                        "visitedConfigurationsJson",
                        json.json(execution.visitedConfigurations())
                )
                .addValue("eventCount", execution.events().size())
                .addValue("lastSequence", last.sequence())
                .addValue("terminalStatus", terminal.map(value -> value.status().name()).orElse(null))
                .addValue("terminalReasonCode", terminal.map(
                        RetrievalObservation::reasonCode
                ).orElse(null))
                .addValue("firstEventAt", OffsetDateTime.ofInstant(firstEventAt, ZoneOffset.UTC))
                .addValue("lastEventAt", OffsetDateTime.ofInstant(lastEventAt, ZoneOffset.UTC))
                .addValue("schemaVersion", first.schemaVersion())
                .addValue("updatedAt", OffsetDateTime.ofInstant(lastEventAt, ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO retrieval_execution_observation (
                    tenant_id, execution_id, request_id, purpose, completeness,
                    incomplete_reasons_json, missing_sequences_json,
                    visited_configurations_json, event_count, last_sequence,
                    terminal_status, terminal_reason_code, first_event_at,
                    last_event_at, schema_version, updated_at
                ) VALUES (
                    :tenantId, :executionId, :requestId, :purpose, :completeness,
                    CAST(:incompleteReasonsJson AS jsonb),
                    CAST(:missingSequencesJson AS jsonb),
                    CAST(:visitedConfigurationsJson AS jsonb),
                    :eventCount, :lastSequence, :terminalStatus, :terminalReasonCode,
                    :firstEventAt, :lastEventAt, :schemaVersion, :updatedAt
                )
                ON CONFLICT (tenant_id, execution_id) DO UPDATE
                    SET request_id = EXCLUDED.request_id,
                        purpose = EXCLUDED.purpose,
                        completeness = EXCLUDED.completeness,
                        incomplete_reasons_json = EXCLUDED.incomplete_reasons_json,
                        missing_sequences_json = EXCLUDED.missing_sequences_json,
                        visited_configurations_json = EXCLUDED.visited_configurations_json,
                        event_count = EXCLUDED.event_count,
                        last_sequence = EXCLUDED.last_sequence,
                        terminal_status = EXCLUDED.terminal_status,
                        terminal_reason_code = EXCLUDED.terminal_reason_code,
                        first_event_at = LEAST(
                            retrieval_execution_observation.first_event_at,
                            EXCLUDED.first_event_at
                        ),
                        last_event_at = GREATEST(
                            retrieval_execution_observation.last_event_at,
                            EXCLUDED.last_event_at
                        ),
                        schema_version = EXCLUDED.schema_version,
                        updated_at = GREATEST(
                            retrieval_execution_observation.updated_at,
                            EXCLUDED.updated_at
                        )
                  WHERE EXCLUDED.event_count >= retrieval_execution_observation.event_count
                """, parameters);
    }
}
