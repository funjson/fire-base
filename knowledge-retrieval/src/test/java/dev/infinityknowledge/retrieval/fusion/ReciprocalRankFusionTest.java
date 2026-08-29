package dev.infinityknowledge.retrieval.fusion;

import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证跨索引融合后仍保留可以回到原文的引用定位。
 */
class ReciprocalRankFusionTest {

    @Test
    void keepsSourceSpansWhenHigherScoredChannelDoesNotProvideThem() {
        UUID chunkId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        DocumentId documentId = DocumentId.random();
        ChunkSourceSpan span = new ChunkSourceSpan(UUID.randomUUID(), 3, 12, 2);
        RetrievalCandidate keyword = candidate(
                chunkId, revisionId, documentId, RetrievalChannel.KEYWORD, 0.70D, List.of(span)
        );
        RetrievalCandidate graph = candidate(
                chunkId, revisionId, documentId, RetrievalChannel.GRAPH, 0.95D, List.of()
        );

        FusedCandidate fused = new ReciprocalRankFusion(60).fuseRankedLists(List.of(
                new RankedCandidateList(
                        "q0:keyword",
                        RetrievalChannel.KEYWORD,
                        1.0D,
                        List.of(keyword)
                ),
                new RankedCandidateList(
                        "q0:graph",
                        RetrievalChannel.GRAPH,
                        1.0D,
                        List.of(graph)
                )
        ), 5).getFirst();

        assertEquals(RetrievalChannel.KEYWORD, fused.representative().channel());
        assertEquals(List.of(span), fused.representative().sourceSpans());
        assertFalse(fused.channels().isEmpty());
        assertEquals(2, fused.channels().size());
    }

    @Test
    void usesBranchWeightsAndRanksInsteadOfRetrieverScores() {
        RetrievalCandidate weakNativeScore = candidate(
                UUID.randomUUID(), UUID.randomUUID(), DocumentId.random(),
                RetrievalChannel.KEYWORD, 0.01D, List.of()
        );
        RetrievalCandidate strongNativeScore = candidate(
                UUID.randomUUID(), UUID.randomUUID(), DocumentId.random(),
                RetrievalChannel.VECTOR, 0.99D, List.of()
        );
        ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);

        List<FusedCandidate> result = fusion.fuseRankedLists(List.of(
                new RankedCandidateList(
                        "q0:keyword", RetrievalChannel.KEYWORD, 2.0D,
                        List.of(weakNativeScore)
                ),
                new RankedCandidateList(
                        "q0:vector", RetrievalChannel.VECTOR, 1.0D,
                        List.of(strongNativeScore)
                )
        ), 2);

        assertEquals(weakNativeScore.chunkId(), result.getFirst().representative().chunkId());
        assertEquals(1.0D, result.getFirst().normalizedRrfScore());
        assertTrue(result.getFirst().rrfScore() > result.get(1).rrfScore());
        assertEquals(2.0D / 61.0D,
                result.getFirst().contributionsByBranch().get("q0:keyword"));
    }

    private static RetrievalCandidate candidate(
            UUID chunkId,
            UUID revisionId,
            DocumentId documentId,
            RetrievalChannel channel,
            double score,
            List<ChunkSourceSpan> sourceSpans
    ) {
        return new RetrievalCandidate(
                chunkId,
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                documentId,
                revisionId,
                channel,
                1,
                score,
                "订单服务故障手册",
                List.of("故障处理"),
                "检查下游依赖。",
                "https://knowledge.example/order-timeout",
                Map.of(),
                sourceSpans
        );
    }
}
