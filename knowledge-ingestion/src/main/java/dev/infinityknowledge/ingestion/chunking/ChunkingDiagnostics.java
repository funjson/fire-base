package dev.infinityknowledge.ingestion.chunking;

/**
 * 单次 Chunk 规划的非敏感诊断指标。
 *
 * <p>这里仅保存数量和大小分布，不保存正文、向量或模型原始响应，可直接进入评测
 * Trace，而不需要把同一诊断复制到每个 Chunk。</p>
 *
 * @param structuralHardBreaks 结构类型、章节或 Element 内硬切产生的不可删除边界
 * @param baselineSoftBreaks 语义建议应用前由目标大小固化的软边界数
 * @param semanticCandidateBoundaries 模型实际评估的相邻 Slice 边界数
 * @param semanticCutSuggestions 模型给出的 CUT 建议数
 * @param semanticJoinSuggestions 模型给出的 JOIN 建议数
 * @param semanticNeutralSuggestions 落在双阈值中间区域的建议数
 * @param semanticCutsAdded 在无边界位置实际新增的 CUT 数
 * @param semanticJoinsApplied 实际删除的基线软边界数
 * @param semanticNoOps 命中既有 CUT 或本来就连通位置的无操作建议数
 * @param tokenLimitBreaks 最终检索文本硬上限产生的不可删除边界数
 * @param spanLimitBreaks SourceSpan 数量硬上限产生的不可删除边界数
 * @param rejectedSemanticJoins 请求越过硬边界或合并后超限而被拒绝的 JOIN 数
 * @param finalChunkCount 最终物化 Chunk 数
 * @param minimumChunkUnits 最小 Chunk contextualText 的计数单位
 * @param averageChunkUnits 平均 Chunk contextualText 的计数单位
 * @param maximumChunkUnits 最大 Chunk contextualText 的计数单位
 */
public record ChunkingDiagnostics(
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
        int rejectedSemanticJoins,
        int finalChunkCount,
        int minimumChunkUnits,
        double averageChunkUnits,
        int maximumChunkUnits
) {

    /** 校验诊断计数与大小分布均为有限非负值。 */
    public ChunkingDiagnostics {
        if (structuralHardBreaks < 0 || baselineSoftBreaks < 0
                || semanticCandidateBoundaries < 0 || semanticCutSuggestions < 0
                || semanticJoinSuggestions < 0 || semanticNeutralSuggestions < 0
                || semanticCutsAdded < 0 || semanticJoinsApplied < 0
                || semanticNoOps < 0 || tokenLimitBreaks < 0
                || spanLimitBreaks < 0 || rejectedSemanticJoins < 0
                || finalChunkCount < 0 || minimumChunkUnits < 0
                || maximumChunkUnits < 0 || !Double.isFinite(averageChunkUnits)
                || averageChunkUnits < 0.0D) {
            throw new IllegalArgumentException("chunking diagnostics must be finite and non-negative");
        }
        if (semanticCandidateBoundaries != semanticCutSuggestions
                + semanticJoinSuggestions + semanticNeutralSuggestions) {
            throw new IllegalArgumentException("semantic suggestion counts are inconsistent");
        }
        if (semanticCutSuggestions + semanticJoinSuggestions
                != semanticCutsAdded + semanticJoinsApplied
                + semanticNoOps + rejectedSemanticJoins) {
            throw new IllegalArgumentException("semantic application counts are inconsistent");
        }
        if (finalChunkCount == 0
                && (minimumChunkUnits != 0 || maximumChunkUnits != 0
                || averageChunkUnits != 0.0D)) {
            throw new IllegalArgumentException("empty chunking result must have zero size metrics");
        }
        if (finalChunkCount > 0
                && (minimumChunkUnits < 1 || maximumChunkUnits < minimumChunkUnits
                || averageChunkUnits < minimumChunkUnits
                || averageChunkUnits > maximumChunkUnits)) {
            throw new IllegalArgumentException("chunk size metrics are inconsistent");
        }
    }
}
