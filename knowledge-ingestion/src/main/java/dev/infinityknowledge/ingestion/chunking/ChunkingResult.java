package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.KnowledgeChunk;

import java.util.List;
import java.util.Objects;

/**
 * 一次统一 Chunk 规划与物化的结果。
 *
 * @param chunks 按文档阅读顺序排列的不可变检索单元
 * @param diagnostics 可进入评测和 Trace 的聚合诊断
 */
public record ChunkingResult(
        List<KnowledgeChunk> chunks,
        ChunkingDiagnostics diagnostics
) {

    /** 保证诊断中的最终数量与实际 Chunk 数量一致。 */
    public ChunkingResult {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        if (chunks.size() != diagnostics.finalChunkCount()) {
            throw new IllegalArgumentException("finalChunkCount differs from chunks size");
        }
    }
}
