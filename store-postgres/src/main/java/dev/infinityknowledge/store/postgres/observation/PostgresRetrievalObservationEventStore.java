package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.evaluation.observation.ObservationConflictException;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL 原始检索事件存储；冲突内容不会被 {@code ON CONFLICT} 静默吞掉。 */
public final class PostgresRetrievalObservationEventStore
        implements RetrievalObservationEventStore {
    private static final String SELECT_COLUMNS = """
            tenant_id, event_id, execution_id, request_id, sequence_number,
            purpose, space_ids_json::text AS space_ids_json,
            visit_index, attempt_index, stage, status, reason_code,
            config_fingerprint, stage_started_at, stage_completed_at,
            schema_version, payload_type, payload_json::text AS payload_json
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObservationJsonCodec json;

    /** 创建原始事件存储适配器。 */
    public PostgresRetrievalObservationEventStore(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new ObservationJsonCodec());
    }

    PostgresRetrievalObservationEventStore(
            NamedParameterJdbcTemplate jdbc,
            ObservationJsonCodec json
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public AppendOutcome append(RetrievalObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        lockExecution(observation);
        MapSqlParameterSource parameters = parameters(observation);
        int inserted = jdbc.update("""
                INSERT INTO retrieval_observation_event (
                    tenant_id, event_id, execution_id, request_id, sequence_number,
                    purpose, space_ids_json, visit_index, attempt_index, stage,
                    status, reason_code, config_fingerprint, stage_started_at,
                    stage_completed_at, schema_version, payload_type, payload_json
                ) VALUES (
                    :tenantId, :eventId, :executionId, :requestId, :sequence,
                    :purpose, CAST(:spaceIdsJson AS jsonb), :visitIndex, :attemptIndex,
                    :stage, :status, :reasonCode, :configFingerprint, :startedAt,
                    :completedAt, :schemaVersion, :payloadType,
                    CAST(:payloadJson AS jsonb)
                )
                ON CONFLICT DO NOTHING
                """, parameters);
        if (inserted == 1) {
            return AppendOutcome.APPENDED;
        }

        List<RetrievalObservation> sameId = jdbc.query(
                "SELECT " + SELECT_COLUMNS + """
                  FROM retrieval_observation_event
                 WHERE tenant_id = :tenantId AND event_id = :eventId
                """,
                parameters,
                this::observation
        );
        if (!sameId.isEmpty()) {
            if (sameId.getFirst().equals(observation)) {
                return AppendOutcome.ALREADY_PRESENT;
            }
            throw new ObservationConflictException(
                    "eventId was reused with different observation content"
            );
        }

        List<UUID> sequenceOwners = jdbc.query("""
                SELECT event_id
                  FROM retrieval_observation_event
                 WHERE tenant_id = :tenantId
                   AND execution_id = :executionId
                   AND sequence_number = :sequence
                """, parameters, (row, number) -> row.getObject("event_id", UUID.class));
        if (!sequenceOwners.isEmpty()) {
            throw new ObservationConflictException(
                    "execution sequence was reused by a different event"
            );
        }
        throw new IllegalStateException("observation insert was not persisted and has no conflict row");
    }

    /**
     * 在监听器事务内按 execution 串行化追加与随后重放，防止并发事件形成丢失投影。
     */
    private void lockExecution(RetrievalObservation observation) {
        jdbc.query("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended(:executionLockKey, 0)
                )
                """,
                new MapSqlParameterSource().addValue(
                        "executionLockKey",
                        observation.tenantId().value() + ":" + observation.executionId()
                ),
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException(
                                "execution advisory lock did not return a result"
                        );
                    }
                    return null;
                }
        );
    }

    @Override
    public List<RetrievalObservation> events(TenantId tenantId, UUID executionId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        return List.copyOf(jdbc.query(
                "SELECT " + SELECT_COLUMNS + """
                  FROM retrieval_observation_event
                 WHERE tenant_id = :tenantId AND execution_id = :executionId
                 ORDER BY sequence_number
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId.value())
                        .addValue("executionId", executionId),
                this::observation
        ));
    }

    private MapSqlParameterSource parameters(RetrievalObservation observation) {
        return new MapSqlParameterSource()
                .addValue("tenantId", observation.tenantId().value())
                .addValue("eventId", observation.eventId())
                .addValue("executionId", observation.executionId())
                .addValue("requestId", observation.requestId())
                .addValue("sequence", observation.sequence())
                .addValue("purpose", observation.purpose().name())
                .addValue("spaceIdsJson", json.spaceIds(observation.spaceIds()))
                .addValue("visitIndex", observation.visitIndex())
                .addValue("attemptIndex", observation.attemptIndex())
                .addValue("stage", observation.stage().name())
                .addValue("status", observation.status().name())
                .addValue("reasonCode", observation.reasonCode())
                .addValue("configFingerprint", observation.configFingerprint())
                .addValue("startedAt", OffsetDateTime.ofInstant(
                        observation.startedAt(), ZoneOffset.UTC
                ))
                .addValue("completedAt", OffsetDateTime.ofInstant(
                        observation.completedAt(), ZoneOffset.UTC
                ))
                .addValue("schemaVersion", observation.schemaVersion())
                .addValue("payloadType", observation.payload().getClass().getSimpleName())
                .addValue("payloadJson", json.json(observation.payload()));
    }

    private RetrievalObservation observation(ResultSet row, int number) throws SQLException {
        RetrievalObservationStage stage = RetrievalObservationStage.valueOf(
                row.getString("stage")
        );
        return new RetrievalObservation(
                row.getObject("event_id", UUID.class),
                row.getObject("execution_id", UUID.class),
                row.getObject("request_id", UUID.class),
                row.getLong("sequence_number"),
                RetrievalObservationPurpose.valueOf(row.getString("purpose")),
                new TenantId(row.getString("tenant_id")),
                json.readSpaceIds(row.getString("space_ids_json")),
                row.getInt("visit_index"),
                row.getInt("attempt_index"),
                stage,
                RetrievalObservationStatus.valueOf(row.getString("status")),
                row.getString("reason_code"),
                row.getString("config_fingerprint"),
                row.getObject("stage_started_at", OffsetDateTime.class).toInstant(),
                row.getObject("stage_completed_at", OffsetDateTime.class).toInstant(),
                row.getInt("schema_version"),
                json.readPayload(
                        stage,
                        row.getString("payload_type"),
                        row.getString("payload_json")
                )
        );
    }
}
