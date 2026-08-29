package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingBudget;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticRefinementChunkerTest {
    private static final TenantId TENANT_ID = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE_ID = new KnowledgeSpaceId("ops");
    private static final DocumentId DOCUMENT_ID = new DocumentId(
            UUID.fromString("10000000-0000-0000-0000-000000000001")
    );
    private static final EmbeddingSpec SPEC = new EmbeddingSpec("fake", "semantic-v2", 2);
    private ExecutorService executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void joinAdviceRemovesTargetSizeSoftBoundary() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeChunker chunker = chunker(
                (texts, spec) -> List.of(vector(0, 1.0D, 0.0D), vector(1, 1.0D, 0.0D)),
                1, 5, 20, 0
        );

        ChunkingResult result = chunker.chunk(
                TENANT_ID,
                SPACE_ID,
                DOCUMENT_ID,
                revisionId,
                List.of(
                        element(revisionId, 0, "aaaa"),
                        element(revisionId, 1, "bbbb")
                )
        );

        assertThat(result.chunks()).singleElement()
                .extracting(KnowledgeChunk::content)
                .isEqualTo("aaaa\n\nbbbb");
        assertThat(result.diagnostics().semanticJoinsApplied()).isEqualTo(1);
        assertThat(result.diagnostics().baselineSoftBreaks()).isEqualTo(1);
    }

    @Test
    void cutAdviceMayProduceSmallChunkAndPreventsOverlapAcrossTopicBoundary() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeChunker chunker = chunker(
                (texts, spec) -> List.of(vector(0, 1.0D, 0.0D), vector(1, -1.0D, 0.0D)),
                4, 10, 20, 3
        );

        ChunkingResult result = chunker.chunk(
                TENANT_ID,
                SPACE_ID,
                DOCUMENT_ID,
                revisionId,
                List.of(element(revisionId, 0, "a"), element(revisionId, 1, "b"))
        );

        assertThat(result.chunks()).extracting(KnowledgeChunk::content)
                .containsExactly("a", "b");
        assertThat(result.chunks().get(1).sourceSpans()).hasSize(1);
        assertThat(result.diagnostics().semanticCutsAdded()).isEqualTo(1);
        assertThat(result.diagnostics().minimumChunkUnits()).isEqualTo(1);
    }

    @Test
    void neutralAdviceKeepsDeterministicTargetDecision() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeChunker chunker = chunker(
                (texts, spec) -> List.of(vector(0, 1.0D, 0.0D), vector(1, 0.7D, 0.714D)),
                1, 5, 20, 0
        );

        ChunkingResult result = chunker.chunk(
                TENANT_ID,
                SPACE_ID,
                DOCUMENT_ID,
                revisionId,
                List.of(element(revisionId, 0, "aaaa"), element(revisionId, 1, "bbbb"))
        );

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.diagnostics().semanticNeutralSuggestions()).isEqualTo(1);
        assertThat(result.diagnostics().baselineSoftBreaks()).isEqualTo(1);
    }

    @Test
    void tokenHardLimitRejectsJoinSuggestion() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeChunker chunker = chunker(
                (texts, spec) -> List.of(vector(0, 1.0D, 0.0D), vector(1, 1.0D, 0.0D)),
                1, 5, 8, 0
        );

        ChunkingResult result = chunker.chunk(
                TENANT_ID,
                SPACE_ID,
                DOCUMENT_ID,
                revisionId,
                List.of(element(revisionId, 0, "aaaa"), element(revisionId, 1, "bbbb"))
        );

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.diagnostics().tokenLimitBreaks()).isEqualTo(1);
        assertThat(result.diagnostics().semanticNoOps()).isZero();
        assertThat(result.diagnostics().rejectedSemanticJoins()).isEqualTo(1);
    }

    @Test
    void semanticCutCanRefineSentenceBoundaryInsideOneElement() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeElement paragraph = element(revisionId, 0, "Alpha. Beta.");
        KnowledgeChunker chunker = chunker(
                (texts, spec) -> List.of(vector(0, 1.0D, 0.0D), vector(1, -1.0D, 0.0D)),
                1, 20, 40, 0
        );

        ChunkingResult result = chunker.chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(paragraph)
        );

        assertThat(result.chunks()).extracting(KnowledgeChunk::content)
                .containsExactly("Alpha.", " Beta.");
        assertThat(result.chunks().getFirst().sourceSpans().getFirst().startOffset()).isZero();
        assertThat(result.chunks().get(1).sourceSpans().getFirst().startOffset()).isEqualTo(6);
    }

    private KnowledgeChunker chunker(
            EmbeddingProvider provider,
            int minimum,
            int target,
            int maximum,
            int overlap
    ) {
        executor = Executors.newSingleThreadExecutor();
        KnowledgeChunkerFactory factory = new KnowledgeChunkerFactory(
                provider,
                SPEC,
                SemanticChunkingBudget.defaults(),
                executor,
                List.of(),
                List.of()
        );
        return factory.create(new ChunkerConfiguration(
                KnowledgeChunkerFactory.SEMANTIC_REFINEMENT,
                Utf8ByteBudgetTokenCounter.ID,
                minimum,
                target,
                maximum,
                overlap,
                "{\"contextSlices\":0,\"embeddingProfileId\":\"fake/semantic-v2@2\","
                        + "\"mergeSimilarityThreshold\":0.85,"
                        + "\"splitSimilarityThreshold\":0.6}"
        ));
    }

    private static KnowledgeElement element(UUID revisionId, int ordinal, String content) {
        return new KnowledgeElement(
                UUID.nameUUIDFromBytes(
                        (revisionId + ":" + ordinal).getBytes(StandardCharsets.UTF_8)
                ),
                revisionId,
                null,
                ElementType.PARAGRAPH,
                ordinal,
                List.of(),
                content,
                Map.of()
        );
    }

    private static EmbeddingVector vector(int index, double... values) {
        return new EmbeddingVector(
                index,
                java.util.Arrays.stream(values).boxed().toList()
        );
    }
}
