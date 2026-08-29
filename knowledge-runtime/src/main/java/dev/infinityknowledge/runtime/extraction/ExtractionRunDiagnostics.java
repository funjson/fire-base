package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ChunkDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.CleaningDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemDiagnostics;

import java.util.Objects;

/** 把引擎领域诊断映射为任务持久化使用的固定、非敏感指标。 */
public final class ExtractionRunDiagnostics {

    private ExtractionRunDiagnostics() {
    }

    /** 复制计数、合同和耗时，不携带正文、向量或模型响应。 */
    public static ItemDiagnostics from(ExtractionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        var diagnostics = result.diagnostics();
        var chunking = diagnostics.chunking().diagnostics();
        return new ItemDiagnostics(
                diagnostics.parse().parserId(),
                result.contracts().processorVersion(),
                result.retainedElements().size(),
                result.chunks().size(),
                diagnostics.parse().duration().toMillis(),
                diagnostics.cleaning().duration().toMillis(),
                diagnostics.chunking().duration().toMillis(),
                new CleaningDiagnostics(
                        diagnostics.cleaning().indexableElements(),
                        diagnostics.cleaning().metadataOnlyElements(),
                        diagnostics.cleaning().reasonCodeCounts()
                ),
                new ChunkDiagnostics(
                        chunking.structuralHardBreaks(),
                        chunking.baselineSoftBreaks(),
                        chunking.semanticCandidateBoundaries(),
                        chunking.semanticCutSuggestions(),
                        chunking.semanticJoinSuggestions(),
                        chunking.semanticNeutralSuggestions(),
                        chunking.semanticCutsAdded(),
                        chunking.semanticJoinsApplied(),
                        chunking.semanticNoOps(),
                        chunking.tokenLimitBreaks(),
                        chunking.spanLimitBreaks(),
                        chunking.rejectedSemanticJoins(),
                        chunking.finalChunkCount(),
                        chunking.minimumChunkUnits(),
                        chunking.averageChunkUnits(),
                        chunking.maximumChunkUnits()
                )
        );
    }
}
