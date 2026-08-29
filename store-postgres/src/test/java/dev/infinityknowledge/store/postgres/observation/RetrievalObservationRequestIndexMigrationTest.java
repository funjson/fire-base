package dev.infinityknowledge.store.postgres.observation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 request 下钻查询拥有与租户隔离和排序一致的数据库索引。 */
class RetrievalObservationRequestIndexMigrationTest {

    @Test
    void indexesTenantRequestAndLatestExecutionOrder() throws IOException {
        String resource = "/db/migration/"
                + "V21__index_retrieval_observation_request.sql";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .toLowerCase(java.util.Locale.ROOT);

            assertTrue(sql.contains(
                    "on retrieval_execution_observation ( "
                            + "tenant_id, request_id, first_event_at desc )"
            ));
            assertTrue(sql.contains("include (execution_id)"));
        }
    }
}
