package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.evaluation.observation.RuntimeMetricFactProjector;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 在线检索观测查询的唯一安全作用域 SQL。
 *
 * <p>所有读侧查询必须从这里取得 execution 范围，确保用途隔离、租户隔离和
 * Space 授权判定不会在不同页面间漂移。阶段诊断在同一 execution 安全范围内，
 * 再按真实访问轮次和物理召回尝试收窄事件。</p>
 */
final class OnlineRetrievalScopeSql {

    Scope executionScope(OnlineRetrievalObservabilityQuery query) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", query.tenantId().value())
                .addValue(
                        "metricDefinitionVersion",
                        RuntimeMetricFactProjector.METRIC_DEFINITION_VERSION
                )
                .addValue("from", OffsetDateTime.ofInstant(query.from(), ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(query.to(), ZoneOffset.UTC))
                .addValue(
                        "maturityCutoff",
                        OffsetDateTime.ofInstant(query.maturityCutoff(), ZoneOffset.UTC)
                )
                .addValue(
                        "authorizedSpaceIds",
                        query.authorizedSpaceIds().stream()
                                .map(spaceId -> spaceId.value())
                                .sorted()
                                .toList()
                );
        query.spaceId().ifPresent(spaceId -> parameters.addValue("spaceId", spaceId.value()));
        query.configFingerprint().ifPresent(config ->
                parameters.addValue("configFingerprint", config)
        );
        query.dataIndexVersion().ifPresent(version ->
                parameters.addValue("dataIndexVersion", version)
        );
        StringBuilder sql = new StringBuilder("""
                WITH scoped_execution AS (
                    SELECT execution.*
                      FROM retrieval_execution_observation execution
                     WHERE execution.tenant_id = :tenantId
                       AND execution.purpose = 'ONLINE'
                       AND execution.first_event_at >= :from
                       AND execution.first_event_at < :to
                       AND NOT EXISTS (
                           SELECT 1
                             FROM retrieval_observation_event permission_event
                             CROSS JOIN LATERAL jsonb_array_elements_text(
                                 permission_event.space_ids_json
                             ) permission_space
                            WHERE permission_event.tenant_id = execution.tenant_id
                              AND permission_event.execution_id = execution.execution_id
                              AND permission_space.value NOT IN (:authorizedSpaceIds)
                       )
                """);
        appendExecutionFilters(sql, query);
        sql.append(")\n");
        return new Scope(sql.toString(), parameters);
    }

    /**
     * 在 execution 安全范围内进一步按真实 visit 和物理 attempt 收窄阶段事件。
     *
     * <p>索引代际来自 Retriever 分支，只能证明同一 visit/attempt 的执行归属；它不被
     * 推断为其他轮次或其他 Space 的因果版本。无版本筛选时保留 execution 的完整链路。</p>
     */
    Scope diagnosticScope(OnlineRetrievalObservabilityQuery query) {
        Scope executionScope = executionScope(query);
        StringBuilder sql = new StringBuilder(executionScope.cte());
        boolean visitFiltered = query.spaceId().isPresent()
                || query.configFingerprint().isPresent();
        if (visitFiltered) {
            appendSelectedVisit(sql, query);
        }
        if (query.dataIndexVersion().isPresent()) {
            appendSelectedAttempt(sql, visitFiltered);
        }
        appendScopedEvent(sql, visitFiltered, query.dataIndexVersion().isPresent());
        return new Scope(sql.toString(), executionScope.parameters());
    }

    private static void appendExecutionFilters(
            StringBuilder sql,
            OnlineRetrievalObservabilityQuery query
    ) {
        if (query.spaceId().isPresent() || query.configFingerprint().isPresent()) {
            sql.append("""
                       AND EXISTS (
                           SELECT 1
                             FROM retrieval_observation_event visit_event
                             CROSS JOIN LATERAL jsonb_array_elements_text(
                                 visit_event.space_ids_json
                             ) actual_space
                            WHERE visit_event.tenant_id = execution.tenant_id
                              AND visit_event.execution_id = execution.execution_id
                              AND visit_event.stage NOT IN (
                                  'EXECUTION_STARTED', 'SPACE_ROUTING'
                              )
                              AND jsonb_array_length(visit_event.space_ids_json) = 1
                    """);
            if (query.spaceId().isPresent()) {
                sql.append(" AND actual_space.value = :spaceId\n");
            }
            appendConfigurationFilter(sql, query, "visit_event");
            if (query.dataIndexVersion().isPresent()) {
                sql.append("""
                          AND EXISTS (
                              SELECT 1
                                FROM retrieval_metric_fact index_fact
                               WHERE index_fact.tenant_id = visit_event.tenant_id
                                 AND index_fact.execution_id = visit_event.execution_id
                                 AND index_fact.fact_type = 'RUNTIME'
                                 AND index_fact.fact_scope = 'EVENT'
                                 AND index_fact.metric_definition_version
                                     = :metricDefinitionVersion
                                 AND index_fact.purpose = 'ONLINE'
                                 AND index_fact.visit_index = visit_event.visit_index
                                 AND index_fact.stage = 'RETRIEVAL_BRANCH'
                                 AND index_fact.data_index_version = :dataIndexVersion
                          )
                        """);
            }
            sql.append("                       )\n");
        }
        if (query.dataIndexVersion().isPresent()
                && query.spaceId().isEmpty()
                && query.configFingerprint().isEmpty()) {
            sql.append("""
                       AND EXISTS (
                           SELECT 1
                             FROM retrieval_metric_fact index_fact
                            WHERE index_fact.tenant_id = execution.tenant_id
                              AND index_fact.execution_id = execution.execution_id
                              AND index_fact.fact_type = 'RUNTIME'
                              AND index_fact.fact_scope = 'EVENT'
                              AND index_fact.metric_definition_version
                                  = :metricDefinitionVersion
                              AND index_fact.purpose = 'ONLINE'
                              AND index_fact.stage = 'RETRIEVAL_BRANCH'
                              AND index_fact.data_index_version = :dataIndexVersion
                       )
                    """);
        }
    }

    private static void appendSelectedVisit(
            StringBuilder sql,
            OnlineRetrievalObservabilityQuery query
    ) {
        sql.append("""
                , selected_visit AS (
                    SELECT DISTINCT visit_event.tenant_id,
                           visit_event.execution_id, visit_event.visit_index
                      FROM retrieval_observation_event visit_event
                      JOIN scoped_execution execution
                        ON execution.tenant_id = visit_event.tenant_id
                       AND execution.execution_id = visit_event.execution_id
                      CROSS JOIN LATERAL jsonb_array_elements_text(
                          visit_event.space_ids_json
                      ) actual_space
                     WHERE visit_event.stage NOT IN (
                         'EXECUTION_STARTED', 'SPACE_ROUTING'
                     )
                       AND jsonb_array_length(visit_event.space_ids_json) = 1
                """);
        if (query.spaceId().isPresent()) {
            sql.append(" AND actual_space.value = :spaceId\n");
        }
        appendConfigurationFilter(sql, query, "visit_event");
        sql.append("                    )\n");
    }

    private static void appendConfigurationFilter(
            StringBuilder sql,
            OnlineRetrievalObservabilityQuery query,
            String visitAlias
    ) {
        if (query.configFingerprint().isEmpty()) {
            return;
        }
        if ("UNRESOLVED".equals(query.configFingerprint().orElseThrow())) {
            sql.append(" AND ")
                    .append(visitAlias)
                    .append(".config_fingerprint = :configFingerprint\n");
            return;
        }
        sql.append("""
                 AND EXISTS (
                     SELECT 1
                       FROM retrieval_observation_event config_event
                      WHERE config_event.tenant_id = visit_event.tenant_id
                        AND config_event.execution_id = visit_event.execution_id
                        AND config_event.visit_index = visit_event.visit_index
                        AND config_event.stage = 'CONFIGURATION_RESOLVED'
                        AND config_event.status IN ('SUCCEEDED', 'DEGRADED')
                        AND config_event.config_fingerprint = :configFingerprint
                 )
                """);
    }

    private static void appendSelectedAttempt(StringBuilder sql, boolean visitFiltered) {
        sql.append("""
                , selected_attempt AS (
                    SELECT DISTINCT branch_fact.tenant_id, branch_fact.execution_id,
                           branch_fact.visit_index, branch_fact.attempt_index
                      FROM retrieval_metric_fact branch_fact
                      JOIN scoped_execution execution
                        ON execution.tenant_id = branch_fact.tenant_id
                       AND execution.execution_id = branch_fact.execution_id
                """);
        if (visitFiltered) {
            sql.append("""
                      JOIN selected_visit visit
                        ON visit.tenant_id = branch_fact.tenant_id
                       AND visit.execution_id = branch_fact.execution_id
                       AND visit.visit_index = branch_fact.visit_index
                    """);
        }
        sql.append("""
                     WHERE branch_fact.fact_type = 'RUNTIME'
                       AND branch_fact.fact_scope = 'EVENT'
                       AND branch_fact.metric_definition_version = :metricDefinitionVersion
                       AND branch_fact.purpose = 'ONLINE'
                       AND branch_fact.stage = 'RETRIEVAL_BRANCH'
                       AND branch_fact.data_index_version = :dataIndexVersion
                )
                """);
    }

    private static void appendScopedEvent(
            StringBuilder sql,
            boolean visitFiltered,
            boolean attemptFiltered
    ) {
        sql.append("""
                , scoped_event AS (
                    SELECT event.*
                      FROM retrieval_observation_event event
                      JOIN scoped_execution execution
                        ON execution.tenant_id = event.tenant_id
                       AND execution.execution_id = event.execution_id
                """);
        if (visitFiltered) {
            sql.append("""
                      JOIN selected_visit visit
                        ON visit.tenant_id = event.tenant_id
                       AND visit.execution_id = event.execution_id
                       AND visit.visit_index = event.visit_index
                    """);
        }
        if (attemptFiltered) {
            sql.append("""
                      JOIN selected_attempt attempt
                        ON attempt.tenant_id = event.tenant_id
                       AND attempt.execution_id = event.execution_id
                       AND attempt.visit_index = event.visit_index
                       AND attempt.attempt_index = event.attempt_index
                    """);
        }
        sql.append("                )\n");
    }

    /** SQL 片段和与其严格配对的命名参数。 */
    record Scope(String cte, MapSqlParameterSource parameters) {
    }
}
