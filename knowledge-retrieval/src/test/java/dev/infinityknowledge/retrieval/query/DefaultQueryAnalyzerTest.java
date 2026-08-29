package dev.infinityknowledge.retrieval.query;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultQueryAnalyzerTest {

    @Test
    void plansOnlyInstalledRetrievalChannels() {
        DefaultQueryAnalyzer analyzer = new DefaultQueryAnalyzer(
                5,
                Set.of(RetrievalChannel.KEYWORD)
        );

        var plan = analyzer.analyze(query("订单服务依赖哪些组件"));

        assertEquals(Set.of(RetrievalChannel.KEYWORD), plan.channels());
        assertEquals(25, plan.candidateLimit());
    }

    @Test
    void plansPublishedPagesAlongsideKeywordEvidence() {
        DefaultQueryAnalyzer analyzer = new DefaultQueryAnalyzer(
                5,
                Set.of(RetrievalChannel.KEYWORD, RetrievalChannel.PAGE)
        );

        var plan = analyzer.analyze(query("订单服务如何排查"));

        assertEquals(
                Set.of(RetrievalChannel.KEYWORD, RetrievalChannel.PAGE),
                plan.channels()
        );
    }

    private static KnowledgeQuery query(String text) {
        return KnowledgeQuery.online(
                UUID.randomUUID(),
                new PrincipalContext(
                        new TenantId("tenant-a"),
                        new PrincipalId("user-a"),
                        Set.of("reader"),
                        Set.of(),
                        false
                ),
                text,
                Set.of(new KnowledgeSpaceId("engineering")),
                5,
                Map.of()
        );
    }
}
