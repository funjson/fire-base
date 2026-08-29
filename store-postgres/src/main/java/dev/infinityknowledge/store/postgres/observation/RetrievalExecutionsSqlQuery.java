package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.evaluation.observation.VisitedRetrievalConfiguration;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityReader;
import dev.infinityknowledge.evaluation.observation.query.RetrievalExecutionPage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.instant;
import static dev.infinityknowledge.store.postgres.observation.OnlineRetrievalJdbcMappers.nullableInteger;

/** 执行在线检索执行记录的筛选、排序和分页查询。 */
final class RetrievalExecutionsSqlQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObservationJsonCodec json;
    private final OnlineRetrievalScopeSql scopeSql;

    RetrievalExecutionsSqlQuery(
            NamedParameterJdbcTemplate jdbc,
            ObservationJsonCodec json,
            OnlineRetrievalScopeSql scopeSql
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.scopeSql = Objects.requireNonNull(scopeSql, "scopeSql must not be null");
    }

    RetrievalExecutionPage execute(
            OnlineRetrievalObservabilityQuery query,
            OnlineRetrievalObservabilityReader.ExecutionPageRequest pageRequest
    ) {
        OnlineRetrievalScopeSql.Scope scope = scopeSql.executionScope(query);
        String terminalFilters = terminalFilters(pageRequest, scope.parameters());
        Long total = jdbc.queryForObject(
                scope.cte() + " SELECT COUNT(*) FROM scoped_execution execution WHERE TRUE "
                        + terminalFilters,
                scope.parameters(),
                Long.class
        );
        long totalItems = total == null ? 0L : total;
        scope.parameters()
                .addValue("limit", pageRequest.size())
                .addValue("offset", Math.multiplyExact(pageRequest.page(), pageRequest.size()));
        List<RetrievalExecutionPage.Item> items = jdbc.query(
                scope.cte() + """
                        SELECT execution_id, request_id, completeness, event_count,
                               terminal_status, terminal_reason_code, technical_status,
                               degraded, retrieval_attempt_count, result_count,
                               first_event_at, last_event_at,
                               visited_configurations_json::text
                                   AS visited_configurations_json,
                               COALESCE((
                                   SELECT jsonb_agg(
                                              actual_visit.space_id
                                              ORDER BY actual_visit.space_id
                                          )::text
                                     FROM (
                                         SELECT DISTINCT actual_space.value AS space_id
                                           FROM retrieval_observation_event visit_event
                                           CROSS JOIN LATERAL jsonb_array_elements_text(
                                               visit_event.space_ids_json
                                           ) actual_space
                                          WHERE visit_event.tenant_id = execution.tenant_id
                                            AND visit_event.execution_id
                                                = execution.execution_id
                                            AND visit_event.stage NOT IN (
                                                'EXECUTION_STARTED', 'SPACE_ROUTING'
                                            )
                                            AND jsonb_array_length(
                                                visit_event.space_ids_json
                                            ) = 1
                                     ) actual_visit
                               ), '[]') AS actual_space_ids_json
                          FROM scoped_execution execution
                         WHERE TRUE
                        """ + terminalFilters + """
                         ORDER BY first_event_at DESC, execution_id DESC
                         LIMIT :limit OFFSET :offset
                        """,
                scope.parameters(),
                this::executionItem
        );
        long pageCount = totalItems == 0L
                ? 0L
                : (totalItems + pageRequest.size() - 1L) / pageRequest.size();
        return new RetrievalExecutionPage(
                pageRequest.page(),
                pageRequest.size(),
                totalItems,
                Math.toIntExact(pageCount),
                items
        );
    }

    private static String terminalFilters(
            OnlineRetrievalObservabilityReader.ExecutionPageRequest pageRequest,
            MapSqlParameterSource parameters
    ) {
        StringBuilder filters = new StringBuilder();
        pageRequest.terminalStatus().ifPresent(status -> {
            parameters.addValue("terminalStatus", status.name());
            filters.append(" AND execution.terminal_status = :terminalStatus");
        });
        pageRequest.stopReason().ifPresent(reason -> {
            parameters.addValue("stopReason", reason.name());
            filters.append(" AND execution.terminal_reason_code = :stopReason");
        });
        return filters.toString();
    }

    private RetrievalExecutionPage.Item executionItem(ResultSet row, int number)
            throws SQLException {
        Instant startedAt = instant(row, "first_event_at");
        Instant lastObservedAt = instant(row, "last_event_at");
        String terminalStatus = row.getString("terminal_status");
        List<VisitedRetrievalConfiguration> configurations = json.readVisitedConfigurations(
                row.getString("visited_configurations_json")
        );
        List<String> spaces = new ArrayList<>(json.readSpaceIds(
                row.getString("actual_space_ids_json")
        ).stream().map(spaceId -> spaceId.value()).toList());
        spaces.addAll(configurations.stream()
                .map(configuration -> configuration.spaceId().value())
                .toList());
        spaces = spaces.stream().distinct().sorted().toList();
        List<String> fingerprints = configurations.stream()
                .map(VisitedRetrievalConfiguration::fingerprint)
                .distinct()
                .sorted()
                .toList();
        return new RetrievalExecutionPage.Item(
                row.getObject("request_id", UUID.class),
                row.getObject("execution_id", UUID.class),
                startedAt,
                lastObservedAt,
                terminalStatus == null ? null : lastObservedAt,
                terminalStatus == null
                        ? null
                        : Duration.between(startedAt, lastObservedAt).toMillis(),
                row.getString("completeness"),
                row.getString("technical_status"),
                terminalStatus,
                row.getString("terminal_reason_code"),
                row.getObject("degraded", Boolean.class),
                nullableInteger(row, "retrieval_attempt_count"),
                nullableInteger(row, "result_count"),
                row.getInt("event_count"),
                spaces,
                fingerprints
        );
    }
}
