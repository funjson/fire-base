package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalOverview;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalStageDiagnostics;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.nullableDouble;
import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.nullableVersion;
import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.percentiles;
import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.rate;

/** 执行在线检索各阶段、分支和优化链的 v4 权威事实聚合。 */
final class OnlineRetrievalStageDiagnosticsSqlQuery {
    private static final String EVENT_FACT_CTE = """
            , v4_event_fact AS (
                SELECT fact.*, event.sequence_number AS source_sequence_number
                  FROM retrieval_metric_fact fact
                  JOIN scoped_event event
                    ON event.tenant_id = fact.tenant_id
                   AND event.event_id = fact.source_event_id
                 WHERE fact.fact_type = 'RUNTIME'
                   AND fact.fact_scope = 'EVENT'
                   AND fact.metric_definition_version = :metricDefinitionVersion
                   AND fact.purpose = 'ONLINE'
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final OnlineRetrievalScopeSql scopeSql;

    OnlineRetrievalStageDiagnosticsSqlQuery(
            NamedParameterJdbcTemplate jdbc,
            OnlineRetrievalScopeSql scopeSql
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.scopeSql = Objects.requireNonNull(scopeSql, "scopeSql must not be null");
    }

    OnlineRetrievalStageDiagnostics execute(OnlineRetrievalObservabilityQuery query) {
        OnlineRetrievalScopeSql.Scope scope = scopeSql.diagnosticScope(query);
        return new OnlineRetrievalStageDiagnostics(
                query.from(),
                query.to(),
                stageRows(scope),
                branchRows(scope),
                fusion(scope),
                rerank(scope),
                coverage(scope),
                chainRows(scope)
        );
    }

    private List<OnlineRetrievalStageDiagnostics.StageRow> stageRows(
            OnlineRetrievalScopeSql.Scope scope
    ) {
        return jdbc.query(
                scope.cte() + EVENT_FACT_CTE + """
                        SELECT fact.stage,
                               COUNT(DISTINCT fact.execution_id) AS execution_count,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.event.count'
                                     AND fact.aggregation = 'COUNT'
                               ), 0) AS event_count,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key =
                                       'retrieval.stage.normal_completion.rate'
                                     AND fact.aggregation = 'RATIO_NUMERATOR'
                               ), 0) AS success_numerator,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key =
                                       'retrieval.stage.normal_completion.rate'
                                     AND fact.aggregation = 'RATIO_DENOMINATOR'
                               ), 0) AS success_denominator,
                               COUNT(*) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.duration.ms'
                                     AND fact.aggregation = 'DISTRIBUTION'
                               ) AS latency_sample_count,
                               percentile_cont(0.50) WITHIN GROUP (
                                   ORDER BY fact.metric_value
                               ) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.duration.ms'
                                     AND fact.aggregation = 'DISTRIBUTION'
                               ) AS latency_p50,
                               percentile_cont(0.95) WITHIN GROUP (
                                   ORDER BY fact.metric_value
                               ) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.duration.ms'
                                     AND fact.aggregation = 'DISTRIBUTION'
                               ) AS latency_p95,
                               percentile_cont(0.99) WITHIN GROUP (
                                   ORDER BY fact.metric_value
                               ) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.duration.ms'
                                     AND fact.aggregation = 'DISTRIBUTION'
                               ) AS latency_p99,
                               MAX(fact.metric_definition_version)
                                   AS metric_definition_version,
                               AVG(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.input.count'
                                     AND fact.aggregation = 'DISTRIBUTION'
                               ) AS average_input_count,
                               AVG(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.output.count'
                                     AND fact.aggregation = 'DISTRIBUTION'
                               ) AS average_output_count
                          FROM v4_event_fact fact
                         GROUP BY fact.stage
                         ORDER BY MIN(fact.source_sequence_number), fact.stage
                        """,
                scope.parameters(),
                (row, number) -> new OnlineRetrievalStageDiagnostics.StageRow(
                        row.getString("stage"),
                        row.getLong("execution_count"),
                        row.getLong("event_count"),
                        nullableVersion(row),
                        rate(row, "success"),
                        percentiles(row, "latency", row.getLong("latency_sample_count")),
                        nullableDouble(row, "average_input_count"),
                        nullableDouble(row, "average_output_count")
                )
        );
    }

    private List<OnlineRetrievalStageDiagnostics.BranchRow> branchRows(
            OnlineRetrievalScopeSql.Scope scope
    ) {
        return jdbc.query(
                scope.cte() + EVENT_FACT_CTE + """
                        SELECT fact.strategy, fact.channel, fact.component_model,
                               fact.data_index_version,
                               COUNT(DISTINCT fact.execution_id) AS execution_count,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.event.count'
                                     AND fact.aggregation = 'COUNT'
                               ), 0) AS event_count,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.branch.success.rate'
                                     AND fact.aggregation = 'RATIO_NUMERATOR'
                               ), 0) AS success_numerator,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.branch.success.rate'
                                     AND fact.aggregation = 'RATIO_DENOMINATOR'
                               ), 0) AS success_denominator,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.branch.empty.rate'
                                     AND fact.aggregation = 'RATIO_NUMERATOR'
                               ), 0) AS empty_numerator,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.branch.empty.rate'
                                     AND fact.aggregation = 'RATIO_DENOMINATOR'
                               ), 0) AS empty_denominator,
                               AVG(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.branch.candidate.count'
                                     AND fact.aggregation = 'DISTRIBUTION'
                               ) AS average_candidate_count,
                               %s
                          FROM v4_event_fact fact
                         WHERE fact.stage = 'RETRIEVAL_BRANCH'
                         GROUP BY fact.strategy, fact.channel, fact.component_model,
                                  fact.data_index_version
                         ORDER BY event_count DESC, fact.strategy, fact.channel,
                                  fact.component_model, fact.data_index_version
                        """.formatted(latencyFields("fact")),
                scope.parameters(),
                (row, number) -> new OnlineRetrievalStageDiagnostics.BranchRow(
                        row.getString("strategy"),
                        row.getString("channel"),
                        row.getString("component_model"),
                        row.getString("data_index_version"),
                        row.getLong("execution_count"),
                        row.getLong("event_count"),
                        rate(row, "success"),
                        rate(row, "empty"),
                        nullableDouble(row, "average_candidate_count"),
                        percentiles(row, "latency", row.getLong("latency_sample_count"))
                )
        );
    }

    private OnlineRetrievalStageDiagnostics.FusionSummary fusion(
            OnlineRetrievalScopeSql.Scope scope
    ) {
        return summary(
                scope,
                "FUSION",
                """
                        COALESCE(SUM(fact.metric_value) FILTER (
                            WHERE fact.metric_key = 'retrieval.fusion.duplicate.rate'
                              AND fact.aggregation = 'RATIO_NUMERATOR'
                        ), 0) AS duplicate_numerator,
                        COALESCE(SUM(fact.metric_value) FILTER (
                            WHERE fact.metric_key = 'retrieval.fusion.duplicate.rate'
                              AND fact.aggregation = 'RATIO_DENOMINATOR'
                        ), 0) AS duplicate_denominator,
                        AVG(fact.metric_value) FILTER (
                            WHERE fact.metric_key =
                                'retrieval.fusion.input_candidate.count'
                              AND fact.aggregation = 'DISTRIBUTION'
                        ) AS average_input_candidate_count,
                        AVG(fact.metric_value) FILTER (
                            WHERE fact.metric_key =
                                'retrieval.fusion.unique_candidate.count'
                              AND fact.aggregation = 'DISTRIBUTION'
                        ) AS average_unique_candidate_count
                        """,
                (row, count) -> new OnlineRetrievalStageDiagnostics.FusionSummary(
                        count,
                        OnlineRetrievalOverview.Rate.of(
                                row.getLong("duplicate_numerator"),
                                row.getLong("duplicate_denominator")
                        ),
                        nullableDouble(row, "average_input_candidate_count"),
                        nullableDouble(row, "average_unique_candidate_count"),
                        percentiles(row, "latency", row.getLong("latency_sample_count"))
                )
        );
    }

    private OnlineRetrievalStageDiagnostics.RerankSummary rerank(
            OnlineRetrievalScopeSql.Scope scope
    ) {
        return summary(
                scope,
                "RERANK",
                """
                        %s,
                        %s,
                        AVG(fact.metric_value) FILTER (
                            WHERE fact.metric_key =
                                'retrieval.rerank.input_candidate.count'
                              AND fact.aggregation = 'DISTRIBUTION'
                        ) AS average_input_candidate_count,
                        AVG(fact.metric_value) FILTER (
                            WHERE fact.metric_key =
                                'retrieval.rerank.output_candidate.count'
                              AND fact.aggregation = 'DISTRIBUTION'
                        ) AS average_output_candidate_count,
                        COALESCE(SUM(fact.metric_value) FILTER (
                            WHERE fact.metric_key = 'retrieval.rerank.model_request.count'
                              AND fact.aggregation = 'SUM'
                        ), 0) AS model_request_count
                        """.formatted(
                        ratioFields("retrieval.rerank.executed.rate", "executed"),
                        ratioFields("retrieval.rerank.fallback.rate", "fallback")
                ),
                (row, count) -> new OnlineRetrievalStageDiagnostics.RerankSummary(
                        count,
                        rate(row, "executed"),
                        rate(row, "fallback"),
                        nullableDouble(row, "average_input_candidate_count"),
                        nullableDouble(row, "average_output_candidate_count"),
                        row.getLong("model_request_count"),
                        percentiles(row, "latency", row.getLong("latency_sample_count"))
                )
        );
    }

    private OnlineRetrievalStageDiagnostics.CoverageSummary coverage(
            OnlineRetrievalScopeSql.Scope scope
    ) {
        return summary(
                scope,
                "COVERAGE_CHECK",
                """
                        COALESCE(SUM(fact.metric_value) FILTER (
                            WHERE fact.metric_key = 'retrieval.coverage.check.count'
                              AND fact.aggregation = 'COUNT'
                        ), 0) AS check_count,
                        COUNT(*) FILTER (
                            WHERE fact.metric_key = 'retrieval.coverage.score'
                              AND fact.aggregation = 'DISTRIBUTION'
                        ) AS measured_count,
                        %s,
                        AVG(fact.metric_value) FILTER (
                            WHERE fact.metric_key = 'retrieval.coverage.score'
                              AND fact.aggregation = 'DISTRIBUTION'
                        ) AS average_score,
                        AVG(fact.metric_value) FILTER (
                            WHERE fact.metric_key =
                                'retrieval.coverage.retained_candidate.count'
                              AND fact.aggregation = 'DISTRIBUTION'
                        ) AS average_retained_candidate_count,
                        COALESCE(SUM(fact.metric_value) FILTER (
                            WHERE fact.metric_key = 'retrieval.coverage.model_request.count'
                              AND fact.aggregation = 'SUM'
                        ), 0) AS model_request_count
                        """.formatted(ratioFields(
                        "retrieval.coverage.sufficient.rate",
                        "sufficient"
                )),
                (row, count) -> new OnlineRetrievalStageDiagnostics.CoverageSummary(
                        count,
                        row.getLong("check_count"),
                        row.getLong("measured_count"),
                        rate(row, "sufficient"),
                        nullableDouble(row, "average_score"),
                        nullableDouble(row, "average_retained_candidate_count"),
                        row.getLong("model_request_count"),
                        percentiles(row, "latency", row.getLong("latency_sample_count"))
                )
        );
    }

    private List<OnlineRetrievalStageDiagnostics.ChainRow> chainRows(
            OnlineRetrievalScopeSql.Scope scope
    ) {
        return jdbc.query(
                scope.cte() + EVENT_FACT_CTE + """
                        SELECT fact.chain_node, fact.strategy,
                               COUNT(DISTINCT fact.execution_id) AS execution_count,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key = 'retrieval.stage.event.count'
                                     AND fact.aggregation = 'COUNT'
                               ), 0) AS event_count,
                               %s,
                               AVG(fact.metric_value) FILTER (
                                   WHERE fact.metric_key =
                                       'retrieval.chain.node.coverage.delta'
                                     AND fact.aggregation = 'DISTRIBUTION'
                               ) AS average_coverage_delta,
                               COALESCE(SUM(fact.metric_value) FILTER (
                                   WHERE fact.metric_key =
                                       'retrieval.chain.node.model_request.count'
                                     AND fact.aggregation = 'SUM'
                               ), 0) AS model_request_count,
                               %s
                          FROM v4_event_fact fact
                         WHERE fact.stage = 'CHAIN_NODE_COMPLETED'
                         GROUP BY fact.chain_node, fact.strategy
                         ORDER BY event_count DESC, fact.chain_node, fact.strategy
                        """.formatted(
                        ratioFields(
                                "retrieval.chain.node.positive_coverage_gain.rate",
                                "positive_gain"
                        ),
                        latencyFields("fact")
                ),
                scope.parameters(),
                (row, number) -> new OnlineRetrievalStageDiagnostics.ChainRow(
                        row.getString("chain_node"),
                        row.getString("strategy"),
                        row.getLong("execution_count"),
                        row.getLong("event_count"),
                        rate(row, "positive_gain"),
                        nullableDouble(row, "average_coverage_delta"),
                        row.getLong("model_request_count"),
                        percentiles(row, "latency", row.getLong("latency_sample_count"))
                )
        );
    }

    private <T> T summary(
            OnlineRetrievalScopeSql.Scope scope,
            String stage,
            String fields,
            SummaryMapper<T> mapper
    ) {
        scope.parameters().addValue("summaryStage", stage);
        String summarySql = scope.cte() + EVENT_FACT_CTE + """
                SELECT COUNT(DISTINCT fact.execution_id) AS execution_count,
                       COALESCE(SUM(fact.metric_value) FILTER (
                           WHERE fact.metric_key = 'retrieval.stage.event.count'
                             AND fact.aggregation = 'COUNT'
                       ), 0) AS event_count,
                       %s,
                       %s
                  FROM v4_event_fact fact
                 WHERE fact.stage = :summaryStage
                """.formatted(fields, latencyFields("fact"));
        return Objects.requireNonNull(jdbc.queryForObject(
                summarySql,
                scope.parameters(),
                (row, number) -> mapper.map(row, row.getLong("execution_count"))
        ), "online retrieval stage summary is missing");
    }

    private static String ratioFields(String metricKey, String alias) {
        return """
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = '%s'
                      AND fact.aggregation = 'RATIO_NUMERATOR'
                ), 0) AS %s_numerator,
                COALESCE(SUM(fact.metric_value) FILTER (
                    WHERE fact.metric_key = '%s'
                      AND fact.aggregation = 'RATIO_DENOMINATOR'
                ), 0) AS %s_denominator
                """.formatted(metricKey, alias, metricKey, alias);
    }

    private static String latencyFields(String alias) {
        return """
                COUNT(*) FILTER (
                    WHERE %s.metric_key = 'retrieval.stage.duration.ms'
                      AND %s.aggregation = 'DISTRIBUTION'
                ) AS latency_sample_count,
                percentile_cont(0.50) WITHIN GROUP (
                    ORDER BY %s.metric_value
                ) FILTER (
                    WHERE %s.metric_key = 'retrieval.stage.duration.ms'
                      AND %s.aggregation = 'DISTRIBUTION'
                ) AS latency_p50,
                percentile_cont(0.95) WITHIN GROUP (
                    ORDER BY %s.metric_value
                ) FILTER (
                    WHERE %s.metric_key = 'retrieval.stage.duration.ms'
                      AND %s.aggregation = 'DISTRIBUTION'
                ) AS latency_p95,
                percentile_cont(0.99) WITHIN GROUP (
                    ORDER BY %s.metric_value
                ) FILTER (
                    WHERE %s.metric_key = 'retrieval.stage.duration.ms'
                      AND %s.aggregation = 'DISTRIBUTION'
                ) AS latency_p99
                """.formatted(
                alias, alias,
                alias, alias, alias,
                alias, alias, alias,
                alias, alias, alias
        );
    }

    @FunctionalInterface
    private interface SummaryMapper<T> {
        T map(ResultSet row, long executionCount) throws SQLException;
    }
}
