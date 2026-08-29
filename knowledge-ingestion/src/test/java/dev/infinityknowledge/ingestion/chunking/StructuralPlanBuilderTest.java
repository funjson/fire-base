package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralPlanBuilderTest {

    @Test
    void earlySemanticCutCannotMoveOrDeleteLaterBaselineBoundary() {
        List<ElementSlice> slices = slices("aaaa", "bbbb", "cccc");
        ChunkSizing sizing = sizing(1, 11, 30);

        StructuralPlanBuilder.Plan plan = new StructuralPlanBuilder().plan(
                slices,
                new ChunkBoundaryAdvice(Set.of(1), Set.of(), 1, 0),
                sizing
        );

        assertThat(plan.chunks()).hasSize(3);
        assertThat(plan.baselineSoftBreaks()).isEqualTo(1);
        assertThat(plan.semanticCutsAdded()).isEqualTo(1);
        assertThat(plan.chunks().get(1).overlapBarrierBefore()).isTrue();
    }

    @Test
    void cutOnExistingSoftBoundaryIsNoOpButStillBlocksOverlap() {
        List<ElementSlice> slices = slices("aaaa", "bbbb");

        StructuralPlanBuilder.Plan plan = new StructuralPlanBuilder().plan(
                slices,
                new ChunkBoundaryAdvice(Set.of(1), Set.of(), 1, 0),
                sizing(1, 5, 20)
        );

        assertThat(plan.baselineSoftBreaks()).isEqualTo(1);
        assertThat(plan.semanticCutsAdded()).isZero();
        assertThat(plan.semanticNoOps()).isEqualTo(1);
        assertThat(plan.chunks()).hasSize(2);
        assertThat(plan.chunks().get(1).overlapBarrierBefore()).isTrue();
    }

    @Test
    void joinWithoutBaselineSoftBoundaryIsNoOp() {
        List<ElementSlice> slices = slices("aaaa", "bbbb");

        StructuralPlanBuilder.Plan plan = new StructuralPlanBuilder().plan(
                slices,
                new ChunkBoundaryAdvice(Set.of(), Set.of(1), 1, 0),
                sizing(1, 20, 30)
        );

        assertThat(plan.chunks()).hasSize(1);
        assertThat(plan.semanticJoinsApplied()).isZero();
        assertThat(plan.semanticNoOps()).isEqualTo(1);
        assertThat(plan.rejectedSemanticJoins()).isZero();
    }

    @Test
    void consecutiveJoinsRecheckCombinedMaximumAfterEachAppliedJoin() {
        List<ElementSlice> slices = slices("aaaa", "bbbb", "cccc");

        StructuralPlanBuilder.Plan plan = new StructuralPlanBuilder().plan(
                slices,
                new ChunkBoundaryAdvice(Set.of(), Set.of(1, 2), 2, 0),
                sizing(1, 5, 12)
        );

        assertThat(plan.baselineSoftBreaks()).isEqualTo(2);
        assertThat(plan.semanticJoinsApplied()).isEqualTo(1);
        assertThat(plan.rejectedSemanticJoins()).isEqualTo(1);
        assertThat(plan.semanticNoOps()).isZero();
        assertThat(plan.chunks()).hasSize(2);
    }

    @Test
    void joinsCrossingStructuralTokenOrSpanLimitsAreRejected() {
        List<ElementSlice> hardSlices = slices("aaaa", "bbbb");
        ElementSlice second = hardSlices.get(1);
        hardSlices = List.of(
                hardSlices.getFirst(),
                new ElementSlice(
                        second.element(),
                        second.startOffset(),
                        second.endOffset(),
                        second.pageNumber(),
                        true
                )
        );
        StructuralPlanBuilder.Plan hard = new StructuralPlanBuilder().plan(
                hardSlices,
                new ChunkBoundaryAdvice(Set.of(), Set.of(1), 1, 0),
                sizing(1, 20, 30)
        );
        StructuralPlanBuilder.Plan token = new StructuralPlanBuilder().plan(
                slices("aaaa", "bbbb"),
                new ChunkBoundaryAdvice(Set.of(), Set.of(1), 1, 0),
                sizing(1, 5, 8)
        );
        List<ElementSlice> spanSlices = slices(
                java.util.stream.IntStream.range(0, 129)
                        .mapToObj(index -> "x")
                        .toArray(String[]::new)
        );
        StructuralPlanBuilder.Plan span = new StructuralPlanBuilder().plan(
                spanSlices,
                new ChunkBoundaryAdvice(Set.of(), Set.of(128), 1, 0),
                sizing(1, 1_000, 2_000)
        );

        assertThat(hard.rejectedSemanticJoins()).isEqualTo(1);
        assertThat(token.rejectedSemanticJoins()).isEqualTo(1);
        assertThat(span.rejectedSemanticJoins()).isEqualTo(1);
        assertThat(hard.semanticNoOps() + token.semanticNoOps() + span.semanticNoOps())
                .isZero();
    }

    private static ChunkSizing sizing(int minimum, int target, int maximum) {
        return new ChunkSizing(
                new Utf8ByteBudgetTokenCounter(),
                minimum,
                target,
                maximum,
                0
        );
    }

    private static List<ElementSlice> slices(String... contents) {
        UUID revisionId = UUID.randomUUID();
        java.util.ArrayList<ElementSlice> slices = new java.util.ArrayList<>();
        for (int index = 0; index < contents.length; index++) {
            KnowledgeElement element = new KnowledgeElement(
                    UUID.nameUUIDFromBytes(
                            (revisionId + ":" + index).getBytes(StandardCharsets.UTF_8)
                    ),
                    revisionId,
                    null,
                    ElementType.PARAGRAPH,
                    index,
                    List.of(),
                    contents[index],
                    Map.of()
            );
            slices.add(new ElementSlice(
                    element,
                    0,
                    element.content().length(),
                    null,
                    false
            ));
        }
        return List.copyOf(slices);
    }
}
