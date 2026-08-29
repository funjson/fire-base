package dev.infinityknowledge.ingestion.chunking;

import java.util.List;

/** 不修改平台确定性结构与大小决策的内置边界策略。 */
final class StructuralBoundaryStrategy implements ChunkBoundaryStrategy {
    static final String VERSION = "structural-boundary-v1";

    @Override
    public String contract() {
        return VERSION;
    }

    @Override
    public ChunkBoundaryAdvice advise(List<ElementSlice> slices) {
        return ChunkBoundaryAdvice.none();
    }
}
