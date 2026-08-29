package dev.infinityknowledge.ingestion.chunking.semantic;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.ingestion.chunking.ElementSlice;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingSemanticBoundaryStrategyTest {
    private static final EmbeddingSpec SPEC = new EmbeddingSpec("fake", "semantic-v2", 2);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @AfterAll
    static void closeExecutor() {
        EXECUTOR.shutdownNow();
    }

    @Test
    void returnsJoinNeutralAndCutFromAbsoluteCosineSimilarity() {
        UUID revisionId = UUID.randomUUID();
        List<ElementSlice> slices = slices(revisionId, "one", "two", "three", "four");
        EmbeddingProvider provider = (texts, spec) -> List.of(
                vector(0, 1.0D, 0.0D),
                vector(1, 1.0D, 0.0D),
                vector(2, 0.6D, 0.8D),
                vector(3, -1.0D, 0.0D)
        );

        var advice = strategy(provider, 0.2D, 0.9D, 0, generousBudget())
                .advise(slices);

        assertThat(advice.joinWithPreviousSliceIndexes()).containsExactly(1);
        assertThat(advice.cutBeforeSliceIndexes()).containsExactly(3);
        assertThat(advice.candidateCount()).isEqualTo(3);
        assertThat(advice.neutralCount()).isEqualTo(1);
    }

    @Test
    void embedsEverySliceOnceAndUsesPairwiseWindowsEvenWhenMeansWouldCancel() {
        UUID revisionId = UUID.randomUUID();
        List<ElementSlice> slices = slices(revisionId, "left-a", "left-b", "right-a", "right-b");
        AtomicReference<List<String>> captured = new AtomicReference<>();
        EmbeddingProvider provider = (texts, spec) -> {
            captured.set(List.copyOf(texts));
            return List.of(
                    vector(0, 1.0D, 0.0D),
                    vector(1, 1.0D, 0.0D),
                    vector(2, -1.0D, 0.0D),
                    vector(3, -1.0D, 0.0D)
            );
        };

        var advice = strategy(provider, -0.5D, 0.9D, 1, generousBudget())
                .advise(slices);

        assertThat(captured.get()).containsExactly("left-a", "left-b", "right-a", "right-b");
        assertThat(advice.cutBeforeSliceIndexes()).containsExactly(2);
        assertThat(advice.neutralCount()).isEqualTo(2);
        assertThat(strategy(provider, -0.5D, 0.9D, 1, generousBudget()).contract())
                .contains("contextSlices=1")
                .contains("embedding-semantic-boundary-v3")
                .doesNotContain("percentile");
    }

    @Test
    void doesNotCallModelAcrossNonSemanticOrDifferentSectionBoundaries() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeElement paragraphA = element(
                revisionId, 0, ElementType.PARAGRAPH, List.of("A"), "a"
        );
        KnowledgeElement code = element(
                revisionId, 1, ElementType.CODE, List.of("A"), "code"
        );
        KnowledgeElement paragraphB = element(
                revisionId, 2, ElementType.PARAGRAPH, List.of("B"), "b"
        );
        EmbeddingProvider provider = (texts, spec) -> {
            throw new AssertionError("provider must not be called");
        };

        var advice = strategy(provider, 0.2D, 0.9D, 0, generousBudget()).advise(List.of(
                slice(paragraphA), slice(code), slice(paragraphB)
        ));

        assertThat(advice.candidateCount()).isZero();
    }

    @Test
    void rejectsInvalidThresholdOrdering() {
        EmbeddingProvider provider = (texts, spec) -> validVectors(texts.size());

        assertThatThrownBy(() -> strategy(
                provider, 0.9D, 0.9D, 0, generousBudget()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split < merge");
        assertThatThrownBy(() -> strategy(
                provider, -1.1D, 0.9D, 0, generousBudget()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrapsProviderFailureWithoutReturningFakeAdvice() {
        EmbeddingProvider provider = (texts, spec) -> {
            throw new IllegalStateException("provider unavailable");
        };

        assertThatThrownBy(() -> strategy(
                provider, 0.2D, 0.9D, 0, generousBudget()
        ).advise(slices(UUID.randomUUID(), "a", "b")))
                .isInstanceOfSatisfying(SemanticChunkingException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(EmbeddingSemanticBoundaryStrategy.PROVIDER_FAILED);
                    assertThat(failure).hasCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void cancelsProviderWhenStageDeadlineExpires() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        EmbeddingProvider provider = (texts, spec) -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException failure) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", failure);
            }
            return validVectors(texts.size());
        };
        SemanticChunkingBudget budget = budget(Duration.ofMillis(20));

        assertThatThrownBy(() -> strategy(provider, 0.2D, 0.9D, 0, budget)
                .advise(slices(UUID.randomUUID(), "a", "b")))
                .isInstanceOfSatisfying(SemanticChunkingException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(EmbeddingSemanticBoundaryStrategy.STAGE_TIMEOUT)
                );
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rejectsInvalidEmbeddingShapeAndZeroVector() {
        assertInvalid((texts, spec) -> List.of(vector(0, 1.0D, 0.0D)));
        assertInvalid((texts, spec) -> List.of(
                vector(1, 1.0D, 0.0D),
                vector(0, 1.0D, 0.0D)
        ));
        assertInvalid((texts, spec) -> List.of(
                vector(0, 1.0D),
                vector(1, 1.0D)
        ));
        assertInvalid((texts, spec) -> List.of(
                vector(0, 0.0D, 0.0D),
                vector(1, 1.0D, 0.0D)
        ));
    }

    @Test
    void rejectsEveryDocumentAndVectorBudgetBeforeCallingProvider() {
        UUID revisionId = UUID.randomUUID();
        List<ElementSlice> two = slices(revisionId, "aa", "bb");
        List<ElementSlice> three = slices(revisionId, "aa", "bb", "cc");

        assertBudgetRejected(
                new SemanticChunkingBudget(2, 100, 10, 10, 10, 100, Duration.ofSeconds(1)),
                three
        );
        assertBudgetRejected(
                new SemanticChunkingBudget(10, 3, 10, 10, 10, 100, Duration.ofSeconds(1)),
                two
        );
        assertBudgetRejected(
                new SemanticChunkingBudget(10, 100, 2, 10, 10, 100, Duration.ofSeconds(1)),
                three
        );
        assertBudgetRejected(
                new SemanticChunkingBudget(10, 100, 10, 1, 10, 100, Duration.ofSeconds(1)),
                two
        );
        assertBudgetRejected(
                new SemanticChunkingBudget(10, 100, 10, 10, 1, 100, Duration.ofSeconds(1)),
                three
        );
        assertBudgetRejected(
                new SemanticChunkingBudget(10, 100, 10, 10, 10, 3, Duration.ofSeconds(1)),
                two
        );
    }

    private static void assertBudgetRejected(
            SemanticChunkingBudget budget,
            List<ElementSlice> slices
    ) {
        AtomicBoolean called = new AtomicBoolean();
        EmbeddingProvider provider = (texts, spec) -> {
            called.set(true);
            return validVectors(texts.size());
        };

        assertThatThrownBy(() -> strategy(provider, 0.2D, 0.9D, 0, budget)
                .advise(slices))
                .isInstanceOfSatisfying(SemanticChunkingException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                EmbeddingSemanticBoundaryStrategy.BUDGET_EXCEEDED
                        )
                );
        assertThat(called).isFalse();
    }

    private static void assertInvalid(EmbeddingProvider provider) {
        assertThatThrownBy(() -> strategy(provider, 0.2D, 0.9D, 0, generousBudget())
                .advise(slices(UUID.randomUUID(), "a", "b")))
                .isInstanceOfSatisfying(SemanticChunkingException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                EmbeddingSemanticBoundaryStrategy.INVALID_EMBEDDING_RESULT
                        )
                );
    }

    private static EmbeddingSemanticBoundaryStrategy strategy(
            EmbeddingProvider provider,
            double split,
            double merge,
            int contextSlices,
            SemanticChunkingBudget budget
    ) {
        return new EmbeddingSemanticBoundaryStrategy(
                provider,
                SPEC,
                split,
                merge,
                contextSlices,
                budget,
                EXECUTOR
        );
    }

    private static SemanticChunkingBudget generousBudget() {
        return budget(Duration.ofSeconds(2));
    }

    private static SemanticChunkingBudget budget(Duration timeout) {
        return new SemanticChunkingBudget(100, 100_000, 100, 10_000, 99, 10_000, timeout);
    }

    private static List<ElementSlice> slices(UUID revisionId, String... contents) {
        List<ElementSlice> slices = new ArrayList<>();
        for (int index = 0; index < contents.length; index++) {
            slices.add(slice(element(
                    revisionId,
                    index,
                    ElementType.PARAGRAPH,
                    List.of("A"),
                    contents[index]
            )));
        }
        return List.copyOf(slices);
    }

    private static ElementSlice slice(KnowledgeElement element) {
        return new ElementSlice(element, 0, element.content().length(), null, false);
    }

    private static KnowledgeElement element(
            UUID revisionId,
            int ordinal,
            ElementType type,
            List<String> sectionPath,
            String content
    ) {
        return new KnowledgeElement(
                UUID.nameUUIDFromBytes(
                        (revisionId + ":" + ordinal).getBytes(StandardCharsets.UTF_8)
                ),
                revisionId,
                null,
                type,
                ordinal,
                sectionPath,
                content,
                Map.of()
        );
    }

    private static List<EmbeddingVector> validVectors(int count) {
        List<EmbeddingVector> vectors = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            vectors.add(vector(index, 1.0D, 0.0D));
        }
        return List.copyOf(vectors);
    }

    private static EmbeddingVector vector(int index, double... values) {
        return new EmbeddingVector(
                index,
                java.util.Arrays.stream(values).boxed().toList()
        );
    }
}
