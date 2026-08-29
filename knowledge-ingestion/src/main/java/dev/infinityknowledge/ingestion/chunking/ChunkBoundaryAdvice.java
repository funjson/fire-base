package dev.infinityknowledge.ingestion.chunking;

import java.util.Objects;
import java.util.Set;

/**
 * Provider 对相邻 ElementSlice 边界给出的受限建议。
 *
 * <p>索引表示“在该 Slice 之前”的边界。CUT 与 JOIN 不能指向同一边界；平台规划器
 * 最终决定建议是否能在结构、Token 和 SourceSpan 硬约束内生效。</p>
 *
 * @param cutBeforeSliceIndexes 建议断开的 Slice 索引
 * @param joinWithPreviousSliceIndexes 建议与上一 Slice 合并的索引
 * @param candidateCount Provider 实际评估的边界数
 * @param neutralCount 既不满足 CUT 也不满足 JOIN 阈值的边界数
 */
public record ChunkBoundaryAdvice(
        Set<Integer> cutBeforeSliceIndexes,
        Set<Integer> joinWithPreviousSliceIndexes,
        int candidateCount,
        int neutralCount
) {

    /** 校验建议集合互斥且计数自洽。 */
    public ChunkBoundaryAdvice {
        cutBeforeSliceIndexes = Set.copyOf(Objects.requireNonNull(
                cutBeforeSliceIndexes,
                "cutBeforeSliceIndexes must not be null"
        ));
        joinWithPreviousSliceIndexes = Set.copyOf(Objects.requireNonNull(
                joinWithPreviousSliceIndexes,
                "joinWithPreviousSliceIndexes must not be null"
        ));
        if (cutBeforeSliceIndexes.stream().anyMatch(joinWithPreviousSliceIndexes::contains)) {
            throw new IllegalArgumentException("a boundary cannot be both CUT and JOIN");
        }
        if (candidateCount < 0 || neutralCount < 0
                || candidateCount != cutBeforeSliceIndexes.size()
                + joinWithPreviousSliceIndexes.size() + neutralCount) {
            throw new IllegalArgumentException("boundary advice counts are inconsistent");
        }
    }

    /** 返回不修改确定性结构规划的空建议。 */
    public static ChunkBoundaryAdvice none() {
        return new ChunkBoundaryAdvice(Set.of(), Set.of(), 0, 0);
    }
}
