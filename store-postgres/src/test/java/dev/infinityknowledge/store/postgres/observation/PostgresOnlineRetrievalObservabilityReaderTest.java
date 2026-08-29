package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 PostgreSQL 在线读侧固定用途、v4 权威事实和 visit/attempt 精确范围。 */
class PostgresOnlineRetrievalObservabilityReaderTest {
    private static final Instant FROM = Instant.parse("2026-08-28T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-29T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void usesUtcBucketsAndActualVisitFilters() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        List<String> statements = new ArrayList<>();
        ResultSet emptyAggregate = mock(ResultSet.class);
        when(emptyAggregate.getLong(anyString())).thenReturn(0L);
        when(emptyAggregate.getObject(anyString())).thenReturn(null);
        doAnswer(invocation -> {
            statements.add(invocation.getArgument(0));
            RowMapper<Object> mapper = invocation.getArgument(2);
            return mapper.mapRow(emptyAggregate, 0);
        }).when(jdbc).queryForObject(
                anyString(),
                any(SqlParameterSource.class),
                any(RowMapper.class)
        );
        doAnswer(invocation -> {
            statements.add(invocation.getArgument(0));
            return List.of();
        }).when(jdbc).query(
                anyString(),
                any(SqlParameterSource.class),
                any(RowMapper.class)
        );
        var query = new OnlineRetrievalObservabilityQuery(
                new TenantId("tenant-a"),
                Set.of(new KnowledgeSpaceId("space-a"), new KnowledgeSpaceId("space-b")),
                Optional.of(new KnowledgeSpaceId("space-a")),
                FROM,
                TO,
                TO.minusSeconds(60),
                Optional.of("a".repeat(64)),
                Optional.of("index-v1")
        );
        var reader = new PostgresOnlineRetrievalObservabilityReader(jdbc);

        reader.overview(query);
        reader.stages(query);

        String allSql = String.join("\n", statements);
        assertTrue(allSql.contains("execution.purpose = 'ONLINE'"));
        assertTrue(allSql.contains("AT TIME ZONE 'UTC'"));
        assertTrue(allSql.contains("config_event.stage = 'CONFIGURATION_RESOLVED'"));
        assertTrue(allSql.contains("selected_visit AS"));
        assertTrue(allSql.contains("selected_attempt AS"));
        assertTrue(allSql.contains("JOIN scoped_event event"));
        assertTrue(allSql.contains("branch_fact.data_index_version"));
        assertTrue(allSql.contains(
                "fact.metric_definition_version = :metricDefinitionVersion"
        ));
        assertTrue(allSql.contains("fact.aggregation = 'RATIO_NUMERATOR'"));
        assertTrue(allSql.contains("fact.aggregation = 'RATIO_DENOMINATOR'"));
        assertFalse(allSql.contains("payload_json"));
    }
}
