package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityQuery;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityReader;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalOverview;
import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalStageDiagnostics;
import dev.infinityknowledge.evaluation.observation.query.RetrievalExecutionPage;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Objects;

/**
 * PostgreSQL 在线检索观测读模型门面。
 *
 * <p>门面只负责实现 SPI 并协调三个稳定的查询职责。用途隔离、租户授权以及
 * Space/配置/索引筛选全部由 {@link OnlineRetrievalScopeSql} 统一生成，避免不同
 * 页面各自维护近似但不一致的安全范围。</p>
 */
public final class PostgresOnlineRetrievalObservabilityReader
        implements OnlineRetrievalObservabilityReader {
    private final OnlineRetrievalOverviewSqlQuery overviewQuery;
    private final OnlineRetrievalStageDiagnosticsSqlQuery stageDiagnosticsQuery;
    private final RetrievalExecutionsSqlQuery executionsQuery;

    /** 创建仅依赖 JDBC 的 PostgreSQL 读侧适配器。 */
    public PostgresOnlineRetrievalObservabilityReader(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new ObservationJsonCodec());
    }

    PostgresOnlineRetrievalObservabilityReader(
            NamedParameterJdbcTemplate jdbc,
            ObservationJsonCodec json
    ) {
        Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(json, "json must not be null");
        OnlineRetrievalScopeSql scopeSql = new OnlineRetrievalScopeSql();
        this.overviewQuery = new OnlineRetrievalOverviewSqlQuery(jdbc, scopeSql);
        this.stageDiagnosticsQuery = new OnlineRetrievalStageDiagnosticsSqlQuery(jdbc, scopeSql);
        this.executionsQuery = new RetrievalExecutionsSqlQuery(jdbc, json, scopeSql);
    }

    @Override
    public OnlineRetrievalOverview overview(OnlineRetrievalObservabilityQuery query) {
        return overviewQuery.execute(Objects.requireNonNull(query, "query must not be null"));
    }

    @Override
    public OnlineRetrievalStageDiagnostics stages(OnlineRetrievalObservabilityQuery query) {
        return stageDiagnosticsQuery.execute(
                Objects.requireNonNull(query, "query must not be null")
        );
    }

    @Override
    public RetrievalExecutionPage executions(
            OnlineRetrievalObservabilityQuery query,
            ExecutionPageRequest pageRequest
    ) {
        return executionsQuery.execute(
                Objects.requireNonNull(query, "query must not be null"),
                Objects.requireNonNull(pageRequest, "pageRequest must not be null")
        );
    }
}
