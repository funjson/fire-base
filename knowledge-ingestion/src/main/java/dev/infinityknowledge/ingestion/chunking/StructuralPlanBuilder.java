package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.ElementType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 先固化确定性结构基线，再把语义 CUT/JOIN 覆盖到既有边界上的平台规划器。
 *
 * <p>两阶段是可解释性的关键：语义 CUT 不能因为重置动态窗口而间接移动后续基线
 * 边界；JOIN 也只能显式删除一个既有软边界，并在删除时重新校验 Token 与引用数量
 * 硬约束。</p>
 */
final class StructuralPlanBuilder {
    /** 结构规划规则版本，必须进入处理契约。 */
    static final String VERSION = "structural-plan-v2";
    /** 投影层单 Chunk 可安全处理的最大精确引用范围数。 */
    static final int MAXIMUM_SOURCE_SPANS_PER_CHUNK = 128;

    /**
     * 构造最终计划，并分别保留语义前基线数量、建议数量与实际生效数量。
     */
    Plan plan(
            List<ElementSlice> slices,
            ChunkBoundaryAdvice advice,
            ChunkSizing sizing
    ) {
        slices = List.copyOf(Objects.requireNonNull(slices, "slices must not be null"));
        Objects.requireNonNull(advice, "advice must not be null");
        Objects.requireNonNull(sizing, "sizing must not be null");
        validateAdvice(advice, slices.size());
        if (slices.isEmpty()) {
            return new Plan(
                    List.of(), 0, 0, advice.candidateCount(),
                    advice.cutBeforeSliceIndexes().size(),
                    advice.joinWithPreviousSliceIndexes().size(),
                    advice.neutralCount(), 0, 0, 0, 0, 0, 0
            );
        }

        BoundaryType[] baseline = baselineBoundaries(slices, sizing);
        int structuralHardBreaks = count(baseline, BoundaryType.STRUCTURAL_HARD);
        int baselineSoftBreaks = count(baseline, BoundaryType.BASELINE_SOFT);
        int tokenLimitBreaks = count(baseline, BoundaryType.TOKEN_LIMIT);
        int spanLimitBreaks = count(baseline, BoundaryType.SPAN_LIMIT);

        BoundaryType[] resolved = baseline.clone();
        boolean[] semanticCutBefore = new boolean[slices.size()];
        int semanticCutsAdded = 0;
        int semanticNoOps = 0;
        for (int index : advice.cutBeforeSliceIndexes().stream().sorted().toList()) {
            semanticCutBefore[index] = true;
            if (resolved[index] == BoundaryType.NONE) {
                resolved[index] = BoundaryType.SEMANTIC_CUT;
                semanticCutsAdded++;
            } else {
                // 命中既有边界只确认“不允许 Overlap”，不能重复计算成新增断点。
                semanticNoOps++;
            }
        }

        int semanticJoinsApplied = 0;
        int rejectedSemanticJoins = 0;
        for (int index : advice.joinWithPreviousSliceIndexes().stream().sorted().toList()) {
            if (resolved[index] == BoundaryType.NONE) {
                // 该位置原本就连通，无需重复应用 JOIN。
                semanticNoOps++;
                continue;
            }
            if (resolved[index] != BoundaryType.BASELINE_SOFT) {
                // 模型请求越过不可删除的结构、Token 或 SourceSpan 边界，必须可观测。
                rejectedSemanticJoins++;
                continue;
            }
            if (canRemoveSoftBoundary(resolved, index, slices, sizing)) {
                resolved[index] = BoundaryType.NONE;
                semanticJoinsApplied++;
            } else {
                rejectedSemanticJoins++;
            }
        }

        return new Plan(
                materialize(slices, resolved, semanticCutBefore),
                structuralHardBreaks,
                baselineSoftBreaks,
                advice.candidateCount(),
                advice.cutBeforeSliceIndexes().size(),
                advice.joinWithPreviousSliceIndexes().size(),
                advice.neutralCount(),
                semanticCutsAdded,
                semanticJoinsApplied,
                semanticNoOps,
                tokenLimitBreaks,
                spanLimitBreaks,
                rejectedSemanticJoins
        );
    }

    /** 只依赖结构与大小配置固化基线，不读取任何语义建议。 */
    private static BoundaryType[] baselineBoundaries(
            List<ElementSlice> slices,
            ChunkSizing sizing
    ) {
        BoundaryType[] boundaries = new BoundaryType[slices.size()];
        Arrays.fill(boundaries, BoundaryType.NONE);
        List<ElementSlice> current = new ArrayList<>();
        current.add(slices.getFirst());
        requireSingleSliceFits(slices.getFirst(), sizing);
        for (int index = 1; index < slices.size(); index++) {
            ElementSlice previous = slices.get(index - 1);
            ElementSlice next = slices.get(index);
            requireSingleSliceFits(next, sizing);
            BoundaryType boundary = deterministicBoundary(previous, next, current, sizing);
            boundaries[index] = boundary;
            if (boundary != BoundaryType.NONE) {
                current.clear();
            }
            current.add(next);
        }
        return boundaries;
    }

    private static BoundaryType deterministicBoundary(
            ElementSlice previous,
            ElementSlice next,
            List<ElementSlice> current,
            ChunkSizing sizing
    ) {
        if (isStructuralHardBoundary(previous, next)) {
            return BoundaryType.STRUCTURAL_HARD;
        }
        if (current.size() >= MAXIMUM_SOURCE_SPANS_PER_CHUNK) {
            return BoundaryType.SPAN_LIMIT;
        }
        List<ElementSlice> candidate = append(current, next);
        int candidateUnits = units(candidate, sizing);
        if (candidateUnits > sizing.maximumTokens()) {
            return BoundaryType.TOKEN_LIMIT;
        }
        if (candidateUnits > sizing.targetTokens()
                && units(current, sizing) >= sizing.minimumTokens()) {
            return BoundaryType.BASELINE_SOFT;
        }
        return BoundaryType.NONE;
    }

    /**
     * JOIN 按当前已应用的边界重新构造左右完整窗口，连续 JOIN 因而会看到前一次
     * 合并后的真实大小，不能逐边局部绕过最大值。
     */
    private static boolean canRemoveSoftBoundary(
            BoundaryType[] resolved,
            int boundaryIndex,
            List<ElementSlice> slices,
            ChunkSizing sizing
    ) {
        int start = 0;
        for (int index = boundaryIndex - 1; index > 0; index--) {
            if (resolved[index] != BoundaryType.NONE) {
                start = index;
                break;
            }
        }
        int end = slices.size();
        for (int index = boundaryIndex + 1; index < resolved.length; index++) {
            if (resolved[index] != BoundaryType.NONE) {
                end = index;
                break;
            }
        }
        List<ElementSlice> merged = slices.subList(start, end);
        return merged.size() <= MAXIMUM_SOURCE_SPANS_PER_CHUNK
                && units(merged, sizing) <= sizing.maximumTokens();
    }

    private static List<PlannedChunk> materialize(
            List<ElementSlice> slices,
            BoundaryType[] boundaries,
            boolean[] semanticCutBefore
    ) {
        List<PlannedChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < slices.size()) {
            int end = start + 1;
            while (end < slices.size() && boundaries[end] == BoundaryType.NONE) {
                end++;
            }
            boolean overlapBarrier = start > 0
                    && (semanticCutBefore[start]
                    || boundaries[start] == BoundaryType.STRUCTURAL_HARD
                    || boundaries[start] == BoundaryType.SPAN_LIMIT
                    || boundaries[start] == BoundaryType.SEMANTIC_CUT);
            chunks.add(new PlannedChunk(slices.subList(start, end), overlapBarrier));
            start = end;
        }
        return List.copyOf(chunks);
    }

    private static boolean isStructuralHardBoundary(
            ElementSlice previous,
            ElementSlice current
    ) {
        if (current.hardBoundaryBefore()
                || current.type() == ElementType.TITLE
                || current.type() == ElementType.HEADING
                || !previous.sectionPath().equals(current.sectionPath())) {
            return true;
        }
        boolean previousIsolated = isIsolated(previous.type());
        boolean currentIsolated = isIsolated(current.type());
        if (!previousIsolated && !currentIsolated) {
            return false;
        }
        // 同一个表格等专用 Element 的行 Slice 可以聚合；与外部内容仍严格隔离。
        return previous.type() != current.type()
                || !previous.elementId().equals(current.elementId());
    }

    private static boolean isIsolated(ElementType type) {
        return type == ElementType.TABLE
                || type == ElementType.CODE
                || type == ElementType.IMAGE
                || type == ElementType.ATTACHMENT;
    }

    private static void validateAdvice(ChunkBoundaryAdvice advice, int sliceCount) {
        if (advice.candidateCount() > Math.max(0, sliceCount - 1)) {
            throw new IllegalArgumentException(
                    "boundary advice candidateCount exceeds available slice boundaries"
            );
        }
        advice.cutBeforeSliceIndexes().forEach(index -> validateBoundaryIndex(index, sliceCount));
        advice.joinWithPreviousSliceIndexes().forEach(
                index -> validateBoundaryIndex(index, sliceCount)
        );
    }

    private static void validateBoundaryIndex(int index, int sliceCount) {
        if (index <= 0 || index >= sliceCount) {
            throw new IllegalArgumentException("boundary advice index is outside slice range");
        }
    }

    private static void requireSingleSliceFits(ElementSlice slice, ChunkSizing sizing) {
        if (units(List.of(slice), sizing) > sizing.maximumTokens()) {
            throw new IllegalStateException(
                    "single ElementSlice contextualText exceeds maximumTokens"
            );
        }
    }

    private static int units(List<ElementSlice> slices, ChunkSizing sizing) {
        return sizing.tokenCounter().count(ElementSlice.contextualText(slices));
    }

    private static List<ElementSlice> append(
            List<ElementSlice> existing,
            ElementSlice appended
    ) {
        List<ElementSlice> result = new ArrayList<>(existing.size() + 1);
        result.addAll(existing);
        result.add(appended);
        return result;
    }

    private static int count(BoundaryType[] boundaries, BoundaryType expected) {
        int count = 0;
        for (BoundaryType boundary : boundaries) {
            if (boundary == expected) {
                count++;
            }
        }
        return count;
    }

    /** 最终 Chunk 的 Slice 范围以及其前方是否禁止复制 Overlap。 */
    record PlannedChunk(List<ElementSlice> slices, boolean overlapBarrierBefore) {
        PlannedChunk {
            slices = List.copyOf(Objects.requireNonNull(slices, "slices must not be null"));
            if (slices.isEmpty()) {
                throw new IllegalArgumentException("planned chunk must not be empty");
            }
        }
    }

    /** 结构、语义建议和最终计划的聚合诊断。 */
    record Plan(
            List<PlannedChunk> chunks,
            int structuralHardBreaks,
            int baselineSoftBreaks,
            int semanticCandidateBoundaries,
            int semanticCutSuggestions,
            int semanticJoinSuggestions,
            int semanticNeutralSuggestions,
            int semanticCutsAdded,
            int semanticJoinsApplied,
            int semanticNoOps,
            int tokenLimitBreaks,
            int spanLimitBreaks,
            int rejectedSemanticJoins
    ) {
        Plan {
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        }
    }

    /** 基线和语义覆盖后的边界类型。 */
    private enum BoundaryType {
        NONE,
        STRUCTURAL_HARD,
        TOKEN_LIMIT,
        SPAN_LIMIT,
        BASELINE_SOFT,
        SEMANTIC_CUT
    }
}
