package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationConflictException;
import dev.infinityknowledge.evaluation.observation.store.MetricFactStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** PostgreSQL 版本化指标事实存储，支持后续由外部观测适配器替换。 */
public final class PostgresMetricFactStore implements MetricFactStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObservationJsonCodec json;

    /** 创建指标事实存储。 */
    public PostgresMetricFactStore(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new ObservationJsonCodec());
    }

    PostgresMetricFactStore(NamedParameterJdbcTemplate jdbc, ObservationJsonCodec json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public AppendSummary appendAll(List<? extends MetricFact> facts) {
        List<? extends MetricFact> immutable = List.copyOf(Objects.requireNonNull(
                facts,
                "facts must not be null"
        ));
        int appended = 0;
        int alreadyPresent = 0;
        for (MetricFact fact : immutable) {
            AppendOutcome outcome = append(fact, FactScope.EVENT);
            if (outcome == AppendOutcome.APPENDED) {
                appended++;
            } else {
                alreadyPresent++;
            }
        }
        return new AppendSummary(appended, alreadyPresent);
    }

    @Override
    public void replaceExecutionSnapshot(List<MetricFact.Runtime> facts) {
        List<MetricFact.Runtime> immutable = validatedExecutionSnapshot(facts);
        MetricFact.Runtime first = immutable.getFirst();
        MapSqlParameterSource identity = new MapSqlParameterSource()
                .addValue("tenantId", first.tenantId().value())
                .addValue("executionId", first.executionId());
        jdbc.update("""
                DELETE FROM retrieval_metric_fact
                 WHERE tenant_id = :tenantId
                   AND execution_id = :executionId
                   AND fact_scope = 'EXECUTION'
                """, identity);
        for (MetricFact.Runtime fact : immutable) {
            if (append(fact, FactScope.EXECUTION) != AppendOutcome.APPENDED) {
                throw new IllegalStateException(
                        "execution metric snapshot replacement did not insert a fresh fact"
                );
            }
        }
    }

    private AppendOutcome append(MetricFact fact, FactScope scope) {
        Objects.requireNonNull(fact, "metric fact must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        String factId = identity(fact, scope);
        String factType = fact instanceof MetricFact.Runtime ? "RUNTIME" : "OFFLINE_GOLD";
        MetricDimensions dimensions = fact.dimensions();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", fact.tenantId().value())
                .addValue("factId", factId)
                .addValue("factType", factType)
                .addValue("factScope", scope.name())
                .addValue("sourceEventId", fact.sourceEventId())
                .addValue("executionId", fact.executionId())
                .addValue("metricKey", fact.metricKey())
                .addValue("metricDefinitionVersion", fact.metricDefinitionVersion())
                .addValue("aggregation", fact.aggregation().name())
                .addValue("metricValue", fact.value())
                .addValue("spaceId", optional(dimensions, MetricDimensions.Key.SPACE))
                .addValue("queryCaseId", optional(dimensions, MetricDimensions.Key.QUERY_CASE))
                .addValue("configFingerprint", dimensions.require(MetricDimensions.Key.CONFIG))
                .addValue("strategy", optional(dimensions, MetricDimensions.Key.STRATEGY))
                .addValue("attemptIndex", optionalInteger(
                        dimensions,
                        MetricDimensions.Key.ATTEMPT
                ))
                .addValue("componentModel", optional(
                        dimensions,
                        MetricDimensions.Key.COMPONENT_MODEL
                ))
                .addValue("dataIndexVersion", optional(
                        dimensions,
                        MetricDimensions.Key.DATA_INDEX_VERSION
                ))
                .addValue("status", dimensions.require(MetricDimensions.Key.STATUS))
                .addValue("technicalStatus", dimensions.require(
                        MetricDimensions.Key.TECHNICAL_STATUS
                ))
                .addValue("terminalStatus", optional(
                        dimensions,
                        MetricDimensions.Key.TERMINAL_STATUS
                ))
                .addValue("stage", optional(dimensions, MetricDimensions.Key.STAGE))
                .addValue("visitIndex", optionalInteger(
                        dimensions,
                        MetricDimensions.Key.VISIT_INDEX
                ))
                .addValue("channel", optional(dimensions, MetricDimensions.Key.CHANNEL))
                .addValue("chainNode", optional(
                        dimensions,
                        MetricDimensions.Key.CHAIN_NODE
                ))
                .addValue("coverageStatus", optional(
                        dimensions,
                        MetricDimensions.Key.COVERAGE_STATUS
                ))
                .addValue("stopReason", optional(
                        dimensions,
                        MetricDimensions.Key.STOP_REASON
                ))
                .addValue("purpose", dimensions.require(MetricDimensions.Key.PURPOSE))
                .addValue("timeSlice", dimensions.require(MetricDimensions.Key.TIME_SLICE))
                .addValue("dimensionsJson", json.json(dimensions.values()))
                .addValue("factJson", json.json(fact))
                .addValue("observedAt", OffsetDateTime.ofInstant(
                        fact.observedAt(), ZoneOffset.UTC
                ));
        if (fact instanceof MetricFact.OfflineGold gold) {
            parameters.addValue("datasetId", gold.datasetId())
                    .addValue("datasetVersion", gold.datasetVersion())
                    .addValue("caseId", gold.caseId());
        } else {
            parameters.addValue("datasetId", null)
                    .addValue("datasetVersion", null)
                    .addValue("caseId", null);
        }
        int inserted = jdbc.update("""
                INSERT INTO retrieval_metric_fact (
                    tenant_id, fact_id, fact_type, fact_scope, source_event_id, execution_id,
                    metric_key, metric_definition_version, aggregation, metric_value,
                    space_id, query_case_id, config_fingerprint, strategy,
                    attempt_index, component_model, data_index_version, status,
                    technical_status, terminal_status, stage, visit_index, channel,
                    chain_node, coverage_status, stop_reason, purpose, time_slice,
                    dataset_id, dataset_version, case_id, dimensions_json, fact_json, observed_at
                ) VALUES (
                    :tenantId, :factId, :factType, :factScope, :sourceEventId, :executionId,
                    :metricKey, :metricDefinitionVersion, :aggregation, :metricValue,
                    :spaceId, CAST(:queryCaseId AS uuid), :configFingerprint, :strategy,
                    :attemptIndex, :componentModel, :dataIndexVersion, :status,
                    :technicalStatus, :terminalStatus, :stage, :visitIndex, :channel,
                    :chainNode, :coverageStatus, :stopReason, :purpose, :timeSlice,
                    :datasetId, :datasetVersion, :caseId, CAST(:dimensionsJson AS jsonb),
                    CAST(:factJson AS jsonb), :observedAt
                )
                ON CONFLICT DO NOTHING
                """, parameters);
        if (inserted == 1) {
            return AppendOutcome.APPENDED;
        }
        List<MetricFact> existing = jdbc.query("""
                SELECT fact_type, fact_json::text AS fact_json
                  FROM retrieval_metric_fact
                 WHERE tenant_id = :tenantId AND fact_id = :factId
                """, parameters, (row, number) -> json.readFact(
                row.getString("fact_type"),
                row.getString("fact_json")
        ));
        if (!existing.isEmpty() && existing.getFirst().equals(fact)) {
            return AppendOutcome.ALREADY_PRESENT;
        }
        throw new ObservationConflictException(
                "metric fact identity was reused with different content"
        );
    }

    private static List<MetricFact.Runtime> validatedExecutionSnapshot(
            List<MetricFact.Runtime> facts
    ) {
        List<MetricFact.Runtime> immutable = List.copyOf(Objects.requireNonNull(
                facts,
                "execution snapshot facts must not be null"
        ));
        if (immutable.isEmpty()) {
            throw new IllegalArgumentException("execution snapshot facts must not be empty");
        }
        MetricFact.Runtime first = immutable.getFirst();
        Set<SnapshotMetricIdentity> identities = new HashSet<>();
        for (MetricFact.Runtime fact : immutable) {
            Objects.requireNonNull(fact, "execution snapshot fact must not be null");
            if (!first.tenantId().equals(fact.tenantId())
                    || !first.executionId().equals(fact.executionId())) {
                throw new IllegalArgumentException(
                        "execution snapshot facts must share tenantId and executionId"
                );
            }
            SnapshotMetricIdentity identity = new SnapshotMetricIdentity(
                    fact.metricKey(),
                    fact.metricDefinitionVersion(),
                    fact.aggregation()
            );
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(
                        "execution snapshot contains a duplicate metric identity"
                );
            }
        }
        return immutable;
    }

    private static String optional(MetricDimensions dimensions, MetricDimensions.Key key) {
        return dimensions.values().get(key);
    }

    private static Integer optionalInteger(
            MetricDimensions dimensions,
            MetricDimensions.Key key
    ) {
        String value = optional(dimensions, key);
        return value == null ? null : Integer.valueOf(value);
    }

    /**
     * 使用长度前缀规范串计算事实身份；执行快照增加作用域前缀，逐事件事实保持既有身份。
     */
    private static String identity(MetricFact fact, FactScope scope) {
        StringBuilder canonical = new StringBuilder();
        if (scope == FactScope.EXECUTION) {
            append(canonical, scope.name());
        }
        append(canonical, fact.getClass().getSimpleName());
        append(canonical, fact.tenantId().value());
        append(canonical, fact.sourceEventId().toString());
        append(canonical, fact.executionId().toString());
        append(canonical, fact.metricKey());
        append(canonical, Integer.toString(fact.metricDefinitionVersion()));
        append(canonical, fact.aggregation().name());
        for (MetricDimensions.Key key : MetricDimensions.Key.values()) {
            String value = fact.dimensions().values().get(key);
            if (value != null) {
                append(canonical, key.name());
                append(canonical, value);
            }
        }
        if (fact instanceof MetricFact.OfflineGold gold) {
            append(canonical, gold.datasetId().toString());
            append(canonical, Long.toString(gold.datasetVersion()));
            append(canonical, gold.caseId().toString());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder value, String part) {
        value.append(part.length()).append(':').append(part);
    }

    private enum AppendOutcome {
        APPENDED,
        ALREADY_PRESENT
    }

    private enum FactScope {
        EVENT,
        EXECUTION
    }

    private record SnapshotMetricIdentity(
            String metricKey,
            int metricDefinitionVersion,
            MetricFact.Aggregation aggregation
    ) {
    }
}
