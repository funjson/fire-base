package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.trace.RetrievalStepTrace;
import dev.infinityknowledge.domain.trace.RetrievalTrace;
import dev.infinityknowledge.spi.trace.TraceSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 使用单个 PostgreSQL 事务追加检索 Trace 和阶段摘要。
 */
public final class PostgresTraceSink implements TraceSink {
    private static final String INSERT_TRACE = """
            INSERT INTO retrieval_trace (
                id, request_id, tenant_id, principal_id, query_hash,
                total_duration_ms, result_count, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;
    private static final String INSERT_STEP = """
            INSERT INTO retrieval_trace_step (
                trace_id, ordinal, step_name, duration_ms,
                input_count, output_count, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trace_id, ordinal) DO NOTHING
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * 创建事务 Trace Sink。
     *
     * @param jdbc JDBC 模板
     * @param transaction 事务模板
     */
    public PostgresTraceSink(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    /**
     * 幂等追加 Trace；主记录和步骤要么全部提交，要么全部回滚。
     *
     * @param trace 检索 Trace
     */
    @Override
    public void append(RetrievalTrace trace) {
        Objects.requireNonNull(trace, "trace must not be null");
        transaction.executeWithoutResult(status -> {
            jdbc.update(
                    INSERT_TRACE,
                    trace.id(),
                    trace.requestId(),
                    trace.tenantId().value(),
                    trace.principalId().value(),
                    trace.queryHash(),
                    trace.totalDuration().toMillis(),
                    trace.resultCount(),
                    trace.createdAt().atOffset(ZoneOffset.UTC)
            );
            int ordinal = 0;
            for (RetrievalStepTrace step : trace.steps()) {
                jdbc.update(
                        INSERT_STEP,
                        trace.id(),
                        ordinal++,
                        step.name(),
                        step.duration().toMillis(),
                        step.inputCount(),
                        step.outputCount(),
                        step.status()
                );
            }
        });
    }
}
