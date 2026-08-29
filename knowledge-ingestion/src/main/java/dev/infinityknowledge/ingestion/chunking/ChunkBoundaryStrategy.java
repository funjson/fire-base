package dev.infinityknowledge.ingestion.chunking;

import java.util.List;

/**
 * 只能建议 ElementSlice 边界的 Chunker Provider 扩展契约。
 *
 * <p>Provider 无权直接构造 KnowledgeChunk。结构硬边界、Token 上限、SourceSpan、
 * Chunk 标识和最终物化全部由平台统一执行。</p>
 */
public interface ChunkBoundaryStrategy {

    /** 返回模型、阈值、预算和实现版本组成的不可变契约。 */
    String contract();

    /** 对平台生成的有序 Slice 给出 CUT、JOIN 或中立建议。 */
    ChunkBoundaryAdvice advise(List<ElementSlice> slices);
}
