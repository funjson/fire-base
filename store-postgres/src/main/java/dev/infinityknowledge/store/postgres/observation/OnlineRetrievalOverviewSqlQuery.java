package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalOverview;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.instant;
import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.nullableDouble;
import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.percentiles;
import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.rate;

/** 执行在线总览聚合、时间序列和配置维度查询。 */
final class OnlineRetrievalOverviewSqlQuery {
    private static final String EXECUTION_FACT_CTE = """
            , execution_fact AS (
                SELECT fact.*
                  FROM retrieval_metric_fact fact
                  JOIN scoped_execution execution
                    ON execution.tenant_id = fact.tenant_id
                   AND execution.execution_id = fact.execution_id
                 WHERE fact.fact_type = 'RUNTIME'
                   AND fact.fact_scope = 'EXECUTION'
                   AND fact.metric_definition_version = :metricDefinitionVersion
                   AND fact.purpose = 'ONLINE'
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final OnlineRetrievalScopeSql scopeSql;

    OnlineRetrievalOverviewSqlQuery(
            NamedParameterJdbcTemplate jdbc,
            OnlineRetrievalScopeSql scopeSql
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.scopeSql = Objects.requireNonNull(scopeSql, "scopeSql must not be null");
    }

    OnlineRetrievalOverview execute(OnlineRetrievalObservabilityQuery query) {
        OnlineRetrievalScopeSql.Scope scope = scopeSql.executionScope(query);
        OnlineRetrievalScopeSql.Scope diagnosticScope = scopeSql.diagnosticScope(query);
        OverviewAggregate aggregate = jdbc.queryForObject(
                scope.cte() + EXECUTION_FACT_CTE + aggregateSql(),
                scope.parameters(),
                this::overviewAggregate
        );
        if (aggregate == null) {
            throw new IllegalStateException("online retrieval overview aggregate is missing");
        }
        return new OnlineRetrievalOverview(
                query.from(),
                query.to(),
                query.granularity(),
                aggregate.requestCount(),
                dimensionCounts(diagnosticScope, "CONFIG"),
                dimensionCounts(diagnosticScope, "DATA_INDEX"),
                OnlineRetrievalOverview.Rate.of(
                        aggregate.technicalSuccessNumerator(),
                        aggregate.technicalSuccessDenominator()
                ),
                OnlineRetrievalOverview.Rate.of(
                        aggregate.degradedNumerator(),
                        aggregate.degradedDenominator()
                ),
                percentiles(
                        aggregate.latencySampleCount(),
                        aggregate.latencyP50(),
                        aggregate.latencyP95(),
                        aggregate.latencyP99()
                ),
                OnlineRetrievalOverview.Rate.of(
                        aggregate.terminalObservationNumerator(),
                        aggregate.terminalObservationDenominator()
                ),
                OnlineRetrievalOverview.Rate.of(
                        aggregate.firstSufficientNumerator(),
                        aggregate.firstSufficientDenominator()
                ),
                OnlineRetrievalOverview.Rate.of(
                        aggregate.finalSufficientNumerator(),
                        aggregate.finalSufficientDenominator()
                ),
                OnlineRetrievalOverview.Rate.of(
                        aggregate.recoveredNumerator(),
                        aggregate.recoveredDenominator()
                ),
                OnlineRetrievalOverview.Rate.of(
                        aggregate.budgetExhaustedNumerator(),
                        aggregate.budgetExhaustedDenominator()
                ),
                OnlineRetrievalOverview.Rate.of(
                        aggregate.completeNumerator(),
                        aggregate.completeDenominator()
                ),
                overviewSeries(query, scope)
        );
    }

    private List<OnlineRetrievalOverview.Point> overviewSeries(
            OnlineRetrievalObservabilityQuery query,
            OnlineRetrievalScopeSql.Scope scope
    ) {
        String bucket = switch (query.granularity()) {
            case MINUTE -> "minute";
            case HOUR -> "hour";
            case DAY -> "day";
        };
        String seriesSql = scope.cte() + EXECUTION_FACT_CTE + """
                SELECT date_trunc('%s', execution.first_event_at AT TIME ZONE 'UTC')
                           AT TIME ZONE 'UTC' AS bucket_start,
                       %s
                  FROM scoped_execution execution
                  JOIN execution_fact fact
                    ON fact.tenant_id = execution.tenant_id
                   AND fact.execution_id = execution.execution_id
                 GROUP BY bucket_start
                 ORDER BY bucket_start
                """.formatted(bucket, overviewFields(false));
        return jdbc.query(seriesSql, scope.parameters(), this::overviewPoint);
    }

    private List<OnlineRetrievalOverview.DimensionCount> dimensionCounts(
            OnlineRetrievalScopeSql.Scope scope,
            String dimension
    ) {
        String selection;
        String condition;
        if ("CONFIG".equals(dimension)) {
            selection = "fact.config_fingerprint";
            condition = "(fact.config_fingerprint <> 'UNRESOLVED' OR ("
                    + "fact.stage NOT IN ('EXECUTION_STARTED', 'SPACE_ROUTING')"
                    + " AND fact.space_id IS NOT NULL))";
        } else if ("DATA_INDEX".equals(dimension)) {
            selection = "fact.data_index_version";
            condition = "fact.stage = 'RETRIEVAL_BRANCH'"
                    + " AND fact.data_index_version IS NOT NULL";
        } else {
            throw new IllegalArgumentException("unsupported observability dimension");
        }
        return jdbc.query(
                scope.cte() + " SELECT " + selection + " AS dimension_value, "
                        + "COUNT(DISTINCT fact.execution_id) AS execution_count "
                        + "FROM retrieval_metric_fact fact "
                        + "JOIN scoped_event event "
                        + "ON event.tenant_id = fact.tenant_id "
                        + "AND event.event_id = fact.source_event_id "
                        + "WHERE fact.fact_type = 'RUNTIME' "
                        + "AND fact.fact_scope = 'EVENT' "
                        + "AND fact.metric_definition_version = :metricDefinitionVersion "
                        + "AND fact.purpose = 'ONLINE' "
                        + "AND " + condition + " GROUP BY dimension_value "
                        + "ORDER BY execution_count DESC, dimension_value",
                scope.parameters(),
                (row, number) -> new OnlineRetrievalOverview.DimensionCount(
                        row.getString("dimension_value"),
                        row.getLong("execution_count")
                )
        );
    }

    private static String aggregateSql() {
        return """
                SELECT %s
                  FROM scoped_execution execution
                  JOIN execution_fact fact
                    ON fact.tenant_id = execution.tenant_id
                   AND fact.execution_id = execution.execution_id
                """.formatted(overviewFields(true));
    }

    /**
     * 总览与趋势共用同一组事实定义；恢复率和预算耗尽率只存在于总览响应。
     */
    private static String overviewFields(boolean includeAggregateOnlyRates) {
        String common = """
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.count'
                      AND fact.aggregation = 'COUNT'
                ), 0) AS request_count,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.technical_success.rate'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                ), 0) AS technical_success_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.technical_success.rate'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                ), 0) AS technical_success_denominator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.degraded.rate'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                ), 0) AS degraded_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.degraded.rate'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                ), 0) AS degraded_denominator,
                COUNT(*) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.duration.ms'
                      AND fact.aggregation = 'DISTRIBUTION'
                ) AS latency_sample_count,
                percentile_cont(0.50) WITHIN GROUP (
                    ORDER BY fact.metric_value
                ) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.duration.ms'
                      AND fact.aggregation = 'DISTRIBUTION'
                ) AS latency_p50,
                percentile_cont(0.95) WITHIN GROUP (
                    ORDER BY fact.metric_value
                ) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.duration.ms'
                      AND fact.aggregation = 'DISTRIBUTION'
                ) AS latency_p95,
                percentile_cont(0.99) WITHIN GROUP (
                    ORDER BY fact.metric_value
                ) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.duration.ms'
                      AND fact.aggregation = 'DISTRIBUTION'
                ) AS latency_p99,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.terminal_observed.rate'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                      AND execution.first_event_at < :maturityCutoff
                ), 0) AS terminal_observation_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.request.terminal_observed.rate'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                      AND execution.first_event_at < :maturityCutoff
                ), 0) AS terminal_observation_denominator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.coverage.first_sufficient.rate'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                ), 0) AS first_sufficient_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.coverage.first_sufficient.rate'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                ), 0) AS first_sufficient_denominator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.coverage.final_sufficient.rate'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                ), 0) AS final_sufficient_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.coverage.final_sufficient.rate'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                ), 0) AS final_sufficient_denominator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.observation.complete.rate'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                      AND execution.first_event_at < :maturityCutoff
                ), 0) AS complete_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.observation.complete.rate'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                      AND execution.first_event_at < :maturityCutoff
                ), 0) AS complete_denominator
                """;
        if (!includeAggregateOnlyRates) {
            return common;
        }
        return common + """
                , COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.coverage.recovery.rate'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                ), 0) AS recovered_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.coverage.recovery.rate'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                ), 0) AS recovered_denominator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.optimization.budget_exhausted.rate'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                ), 0) AS budget_exhausted_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = 'retrieval.optimization.budget_exhausted.rate'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                ), 0) AS budget_exhausted_denominator
                """;
    }

    private OverviewAggregate overviewAggregate(ResultSet row, int number) throws SQLException {
        return new OverviewAggregate(
                row.getLong("request_count"),
                row.getLong("technical_success_numerator"),
                row.getLong("technical_success_denominator"),
                row.getLong("degraded_numerator"),
                row.getLong("degraded_denominator"),
                row.getLong("latency_sample_count"),
                nullableDouble(row, "latency_p50"),
                nullableDouble(row, "latency_p95"),
                nullableDouble(row, "latency_p99"),
                row.getLong("terminal_observation_numerator"),
                row.getLong("terminal_observation_denominator"),
                row.getLong("first_sufficient_numerator"),
                row.getLong("first_sufficient_denominator"),
                row.getLong("final_sufficient_numerator"),
                row.getLong("final_sufficient_denominator"),
                row.getLong("recovered_numerator"),
                row.getLong("recovered_denominator"),
                row.getLong("budget_exhausted_numerator"),
                row.getLong("budget_exhausted_denominator"),
                row.getLong("complete_numerator"),
                row.getLong("complete_denominator")
        );
    }

    private OnlineRetrievalOverview.Point overviewPoint(ResultSet row, int number)
            throws SQLException {
        long latencySamples = row.getLong("latency_sample_count");
        return new OnlineRetrievalOverview.Point(
                instant(row, "bucket_start"),
                row.getLong("request_count"),
                rate(row, "technical_success"),
                rate(row, "degraded"),
                percentiles(
                        latencySamples,
                        nullableDouble(row, "latency_p50"),
                        nullableDouble(row, "latency_p95"),
                        nullableDouble(row, "latency_p99")
                ),
                rate(row, "terminal_observation"),
                rate(row, "first_sufficient"),
                rate(row, "final_sufficient"),
                rate(row, "complete")
        );
    }

    private record OverviewAggregate(
            long requestCount,
            long technicalSuccessNumerator,
            long technicalSuccessDenominator,
            long degradedNumerator,
            long degradedDenominator,
            long latencySampleCount,
            Double latencyP50,
            Double latencyP95,
            Double latencyP99,
            long terminalObservationNumerator,
            long terminalObservationDenominator,
            long firstSufficientNumerator,
            long firstSufficientDenominator,
            long finalSufficientNumerator,
            long finalSufficientDenominator,
            long recoveredNumerator,
            long recoveredDenominator,
            long budgetExhaustedNumerator,
            long budgetExhaustedDenominator,
            long completeNumerator,
            long completeDenominator
    ) {
    }
}
