package dev.infinityknowledge.store.postgres.observation;

import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalOverview;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** 在线检索观测读模型共用的 JDBC 值映射规则。 */
final class OnlineRetrievalJdbcMappers {
    private OnlineRetrievalJdbcMappers() {
    }

    static OnlineRetrievalOverview.Rate rate(ResultSet row, String prefix)
            throws SQLException {
        return OnlineRetrievalOverview.Rate.of(
                row.getLong(prefix + "_numerator"),
                row.getLong(prefix + "_denominator")
        );
    }

    static OnlineRetrievalOverview.Percentiles percentiles(
            ResultSet row,
            String prefix,
            long sampleCount
    ) throws SQLException {
        return percentiles(
                sampleCount,
                nullableDouble(row, prefix + "_p50"),
                nullableDouble(row, prefix + "_p95"),
                nullableDouble(row, prefix + "_p99")
        );
    }

    static OnlineRetrievalOverview.Percentiles percentiles(
            long sampleCount,
            Double p50,
            Double p95,
            Double p99
    ) {
        if (sampleCount == 0L) {
            return OnlineRetrievalOverview.Percentiles.empty();
        }
        return new OnlineRetrievalOverview.Percentiles(sampleCount, p50, p95, p99);
    }

    static Double nullableDouble(ResultSet row, String column) throws SQLException {
        Number value = (Number) row.getObject(column);
        return value == null ? null : value.doubleValue();
    }

    static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        Number value = (Number) row.getObject(column);
        return value == null ? null : value.intValue();
    }

    static Integer nullableVersion(ResultSet row) throws SQLException {
        return nullableInteger(row, "metric_definition_version");
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }
}
