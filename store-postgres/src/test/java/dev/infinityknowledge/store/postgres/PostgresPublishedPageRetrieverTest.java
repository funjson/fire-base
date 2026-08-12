package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PostgresPublishedPageRetrieverTest {

    @Test
    void denyAllScopeNeverTouchesPageContent() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresPublishedPageRetriever retriever = new PostgresPublishedPageRetriever(jdbc);
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeQuery query = new KnowledgeQuery(
                UUID.randomUUID(),
                new PrincipalContext(
                        tenantId,
                        new PrincipalId("reader"),
                        Set.of("knowledge-reader"),
                        Set.of(),
                        false
                ),
                "订单服务",
                Set.of(),
                5,
                Map.of()
        );
        QueryPlan plan = new QueryPlan(
                query.text(),
                query.text(),
                Set.of(RetrievalChannel.PAGE),
                25
        );

        var result = retriever.retrieve(new RetrievalRequest(
                query,
                plan,
                AccessScope.denyAll(tenantId)
        ));

        assertTrue(result.isEmpty());
        assertEquals(RetrievalChannel.PAGE, retriever.channel());
        verifyNoInteractions(jdbc);
    }
}
