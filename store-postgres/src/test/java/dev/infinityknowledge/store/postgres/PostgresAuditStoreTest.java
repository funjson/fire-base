package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresAuditStoreTest {

    @Test
    void alwaysUsesTenantPredicateAndBoundedPageArguments() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        PostgresAuditStore store = new PostgresAuditStore(jdbc);

        var page = store.find(new TenantId("tenant-a"), 25, 5);

        assertEquals(0, page.total());
        assertTrue(jdbc.countSql.contains("WHERE tenant_id = ?"));
        assertArrayEquals(new Object[]{"tenant-a"}, jdbc.countArguments);
        assertTrue(jdbc.pageSql.contains("WHERE tenant_id = ?"));
        assertArrayEquals(new Object[]{"tenant-a", 25, 5}, jdbc.pageArguments);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String countSql;
        private Object[] countArguments;
        private String pageSql;
        private Object[] pageArguments;

        @Override
        public <T> T queryForObject(
                String sql,
                Class<T> requiredType,
                Object... args
        ) {
            countSql = sql;
            countArguments = args.clone();
            return requiredType.cast(0L);
        }

        @Override
        public <T> List<T> query(
                String sql,
                RowMapper<T> rowMapper,
                Object... args
        ) {
            pageSql = sql;
            pageArguments = args.clone();
            return List.of();
        }
    }
}
