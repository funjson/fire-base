package dev.infinityknowledge.runtime.rerank;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CosineEmbeddingRerankerTest {
    private static final EmbeddingSpec SPEC = new EmbeddingSpec("test", "test-model", 2);

    @Test
    void embedsQueryAndCandidatesOnceAndUsesStableCosineOrder() {
        AtomicInteger invocations = new AtomicInteger();
        EmbeddingProvider provider = (texts, spec) -> {
            invocations.incrementAndGet();
            return List.of(
                    vector(0, 1.0D, 0.0D),
                    vector(1, 0.0D, 1.0D),
                    vector(2, 1.0D, 0.0D),
                    vector(3, 1.0D, 0.0D)
            );
        };
        CosineEmbeddingReranker reranker = reranker(provider, 8, 100, 100, 500);
        RetrievalCandidate alpha = candidate("alpha", 1);
        RetrievalCandidate beta = candidate("beta", 2);
        RetrievalCandidate sameAsBeta = candidate("same", 3);

        List<RetrievalCandidate> result = reranker.rerank(
                "query",
                List.of(alpha, beta, sameAsBeta),
                3
        );

        assertEquals(1, invocations.get());
        assertEquals(List.of(beta, sameAsBeta, alpha), result);
    }

    @Test
    void enforcesCandidateAndCharacterBudgetsBeforeProviderInvocation() {
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<List<String>> observedTexts = new AtomicReference<>();
        EmbeddingProvider provider = (texts, spec) -> {
            invocations.incrementAndGet();
            observedTexts.set(List.copyOf(texts));
            List<EmbeddingVector> vectors = new ArrayList<>();
            for (int index = 0; index < texts.size(); index++) {
                vectors.add(vector(index, 1.0D, 0.0D));
            }
            return vectors;
        };
        CosineEmbeddingReranker reranker = reranker(provider, 2, 3, 4, 9);

        List<RetrievalCandidate> result = reranker.rerank(
                "query-long",
                List.of(candidate("one", 1), candidate("two", 2), candidate("three", 3)),
                3
        );

        assertEquals(1, invocations.get());
        assertEquals(3, result.size());
        assertEquals("three", result.getLast().title());
        assertEquals(List.of(3, 4, 2), observedTexts.get().stream()
                .map(String::length)
                .toList());
    }

    @Test
    void rejectsIncompleteOrDimensionallyInvalidEmbeddingResults() {
        CosineEmbeddingReranker incomplete = reranker(
                (texts, spec) -> List.of(vector(0, 1.0D, 0.0D)),
                8,
                100,
                100,
                500
        );
        CosineEmbeddingReranker wrongDimensions = reranker(
                (texts, spec) -> List.of(
                        new EmbeddingVector(0, List.of(1.0D)),
                        new EmbeddingVector(1, List.of(1.0D))
                ),
                8,
                100,
                100,
                500
        );

        assertThrows(
                IllegalStateException.class,
                () -> incomplete.rerank("query", List.of(candidate("one", 1)), 1)
        );
        assertThrows(
                IllegalStateException.class,
                () -> wrongDimensions.rerank("query", List.of(candidate("one", 1)), 1)
        );
    }

    @Test
    void rejectsZeroVectorsInsteadOfProducingUndefinedSimilarity() {
        CosineEmbeddingReranker reranker = reranker(
                (texts, spec) -> List.of(
                        vector(0, 0.0D, 0.0D),
                        vector(1, 1.0D, 0.0D)
                ),
                8,
                100,
                100,
                500
        );

        assertThrows(
                IllegalStateException.class,
                () -> reranker.rerank("query", List.of(candidate("one", 1)), 1)
        );
    }

    private static CosineEmbeddingReranker reranker(
            EmbeddingProvider provider,
            int maxCandidates,
            int maxQueryCharacters,
            int maxCandidateCharacters,
            int maxTotalCharacters
    ) {
        return new CosineEmbeddingReranker(
                provider,
                SPEC,
                maxCandidates,
                maxQueryCharacters,
                maxCandidateCharacters,
                maxTotalCharacters
        );
    }

    private static EmbeddingVector vector(int index, double first, double second) {
        return new EmbeddingVector(index, List.of(first, second));
    }

    private static RetrievalCandidate candidate(String title, int rank) {
        return new RetrievalCandidate(
                UUID.randomUUID(),
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                DocumentId.random(),
                UUID.randomUUID(),
                RetrievalChannel.KEYWORD,
                rank,
                0.5D,
                title,
                List.of("section"),
                "candidate body",
                "https://knowledge.example/" + title,
                Map.of()
        );
    }
}
