package dev.infinityknowledge.controlplane.api.document.extraction;

import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.controlplane.api.document.DocumentProcessingContractView;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionPreview;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunItem;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** TEST_ONLY 与 INGEST 共用的多文件抽取任务只读响应模型。 */
public final class ExtractionRunViews {

    private ExtractionRunViews() {
    }

    /** 列表使用的任务摘要，不复制逐文件明细。 */
    public record Summary(
            UUID id,
            String spaceId,
            String mode,
            String status,
            String errorCode,
            long configVersion,
            String configFingerprint,
            String datasetId,
            UUID baselineRunId,
            String gateStatus,
            int totalItems,
            long succeededItems,
            long skippedDuplicateItems,
            long failedItems,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant heartbeatAt
    ) {

        /** 从存储快照创建安全摘要。 */
        public static Summary from(RunSnapshot run) {
            return new Summary(
                    run.id(),
                    run.spaceId().value(),
                    run.mode().name(),
                    run.status().name(),
                    run.errorCode(),
                    run.configVersion(),
                    run.configSnapshot().fingerprint(),
                    run.datasetId(),
                    run.baselineRunId(),
                    run.gateStatus().name(),
                    run.totalItems(),
                    run.succeededItems(),
                    run.skippedDuplicateItems(),
                    run.failedItems(),
                    run.createdAt(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.heartbeatAt()
            );
        }
    }

    /** 任务详情包含每个文件的状态和非敏感聚合诊断。 */
    public record Detail(
            UUID id,
            String spaceId,
            String mode,
            String status,
            String errorCode,
            long configVersion,
            String configFingerprint,
            ConfigSnapshot configSnapshot,
            String datasetId,
            UUID baselineRunId,
            String gateStatus,
            GateReport gateReport,
            int totalItems,
            long succeededItems,
            long skippedDuplicateItems,
            long failedItems,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant heartbeatAt,
            List<Item> items
    ) {

        /** 从同一次数据库快照创建任务详情。 */
        public static Detail from(RunSnapshot run) {
            return new Detail(
                    run.id(),
                    run.spaceId().value(),
                    run.mode().name(),
                    run.status().name(),
                    run.errorCode(),
                    run.configVersion(),
                    run.configSnapshot().fingerprint(),
                    ConfigSnapshot.from(run.configSnapshot()),
                    run.datasetId(),
                    run.baselineRunId(),
                    run.gateStatus().name(),
                    GateReport.from(run.gateReport()),
                    run.totalItems(),
                    run.succeededItems(),
                    run.skippedDuplicateItems(),
                    run.failedItems(),
                    run.createdAt(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.heartbeatAt(),
                    run.items().stream().map(item -> Item.from(item, run.mode())).toList()
            );
        }
    }

    /**
     * 单个来源文件的抽取状态。
     *
     * <p>{@code externalId/title/authority} 是正式发布的业务身份，只对 INGEST
     * 返回任务创建时固化的真实值；TEST_ONLY 不发布文档，因此返回 {@code null}，
     * 避免把测试任务内部的占位发布属性误解为正式数据。</p>
     */
    public record Item(
            UUID id,
            UUID sourceAssetId,
            String fileName,
            String mediaType,
            long contentLength,
            String checksumSha256,
            String externalId,
            String title,
            Integer authority,
            String status,
            String stage,
            String errorCode,
            Diagnostics diagnostics,
            Preview preview,
            UUID documentId,
            UUID revisionId
    ) {

        /** 不向页面返回 objectId 或 storageId。 */
        private static Item from(RunItem item, ExtractionMode mode) {
            var source = item.sourceAsset();
            var publication = mode == ExtractionMode.INGEST ? item.publication() : null;
            return new Item(
                    item.id(),
                    source.id(),
                    source.fileName(),
                    source.mediaType(),
                    source.contentLength(),
                    source.checksumSha256(),
                    publication == null ? null : publication.externalId(),
                    publication == null ? null : publication.title(),
                    publication == null ? null : publication.authority(),
                    item.status().name(),
                    item.stage().name(),
                    item.errorCode(),
                    Diagnostics.from(item.diagnostics()),
                    Preview.from(item.preview()),
                    item.documentId() == null ? null : item.documentId().value(),
                    item.revisionId()
            );
        }
    }

    /** 创建时固化的完整有效配置，不包含 Space 配置的创建主体和固化时间。 */
    public record ConfigSnapshot(
            DocumentProcessingContractView processingContract,
            String normalizerContract,
            Map<String, String> parserSelections,
            ConfigCleaning cleaning,
            ConfigChunker chunker,
            String fingerprint
    ) {
        private static ConfigSnapshot from(ExtractionConfigSnapshot value) {
            return new ConfigSnapshot(
                    DocumentProcessingContractView.from(
                            value.processingContract()
                    ),
                    value.normalizerContract(),
                    value.parserSelections(),
                    ConfigCleaning.from(value.cleaning()),
                    ConfigChunker.from(value.chunker()),
                    value.fingerprint()
            );
        }
    }

    /** Cleaner 快照。 */
    public record ConfigCleaning(
            String header,
            String footer,
            String pageNumber,
            String watermark,
            String frontMatter
    ) {
        private static ConfigCleaning from(ExtractionConfigSnapshot.Cleaning value) {
            return new ConfigCleaning(
                    value.header().name(),
                    value.footer().name(),
                    value.pageNumber().name(),
                    value.watermark().name(),
                    value.frontMatter().name()
            );
        }
    }

    /** Chunker 和 Tokenizer 快照。 */
    public record ConfigChunker(
            String providerId,
            String tokenizerId,
            int minimumTokens,
            int targetTokens,
            int maximumTokens,
            int overlapTokens,
            String providerConfigurationJson
    ) {
        private static ConfigChunker from(ExtractionConfigSnapshot.Chunker value) {
            return new ConfigChunker(
                    value.providerId(),
                    value.tokenizerId(),
                    value.minimumTokens(),
                    value.targetTokens(),
                    value.maximumTokens(),
                    value.overlapTokens(),
                    value.providerConfigurationJson()
            );
        }
    }

    /** 有界 Element/Chunk 预览，不包含完整原件和 contextualText。 */
    public record Preview(
            UUID artifactId,
            int artifactLength,
            int totalElementCount,
            int totalChunkCount,
            boolean truncated,
            List<PreviewElement> elements,
            List<PreviewChunk> chunks
    ) {
        private static Preview from(ExtractionPreview value) {
            if (value == null) {
                return null;
            }
            return new Preview(
                    value.artifactId(),
                    value.artifactLength(),
                    value.totalElementCount(),
                    value.totalChunkCount(),
                    value.truncated(),
                    value.elements().stream().map(PreviewElement::from).toList(),
                    value.chunks().stream().map(PreviewChunk::from).toList()
            );
        }
    }

    /** Element 结构和可选 Artifact 来源范围。 */
    public record PreviewElement(
            UUID id,
            UUID parentId,
            String type,
            int ordinal,
            List<String> sectionPath,
            String role,
            String cleaningAction,
            String cleaningReasonCode,
            PreviewArtifactRange sourceRange,
            int contentLength,
            String text,
            boolean textTruncated
    ) {
        private static PreviewElement from(ExtractionPreview.Element value) {
            return new PreviewElement(
                    value.id(),
                    value.parentId(),
                    value.type().name(),
                    value.ordinal(),
                    value.sectionPath(),
                    value.role(),
                    value.cleaningAction(),
                    value.cleaningReasonCode(),
                    PreviewArtifactRange.from(value.sourceRange()),
                    value.contentLength(),
                    value.text(),
                    value.textTruncated()
            );
        }
    }

    /** Chunk 展示正文和 Element 内边界。 */
    public record PreviewChunk(
            UUID id,
            int ordinal,
            List<String> sectionPath,
            int contentLength,
            String text,
            boolean textTruncated,
            List<PreviewElementRange> sourceSpans
    ) {
        private static PreviewChunk from(ExtractionPreview.Chunk value) {
            return new PreviewChunk(
                    value.id(),
                    value.ordinal(),
                    value.sectionPath(),
                    value.contentLength(),
                    value.text(),
                    value.textTruncated(),
                    value.sourceSpans().stream().map(PreviewElementRange::from).toList()
            );
        }
    }

    /** Chunk 在 Element 内以及 Artifact 内的范围。 */
    public record PreviewElementRange(
            UUID elementId,
            int startOffset,
            int endOffset,
            Integer pageNumber,
            PreviewArtifactRange artifactRange
    ) {
        private static PreviewElementRange from(ExtractionPreview.ElementRange value) {
            return new PreviewElementRange(
                    value.elementId(),
                    value.startOffset(),
                    value.endOffset(),
                    value.pageNumber(),
                    PreviewArtifactRange.from(value.artifactRange())
            );
        }
    }

    /** 规范化 Artifact 中的全局 UTF-16 范围。 */
    public record PreviewArtifactRange(
            UUID artifactId,
            int startOffset,
            int endOffset,
            Integer pageNumber
    ) {
        private static PreviewArtifactRange from(ExtractionPreview.ArtifactRange value) {
            return value == null ? null : new PreviewArtifactRange(
                    value.artifactId(),
                    value.startOffset(),
                    value.endOffset(),
                    value.pageNumber()
            );
        }
    }

    /** 真实 Dataset Runner 报告；未评测时为 null。 */
    public record GateReport(
            String status,
            String datasetId,
            String datasetVersion,
            String configFingerprint,
            Instant evaluatedAt,
            List<GateCase> cases,
            String errorCode
    ) {
        private static GateReport from(ExtractionGateReport value) {
            if (value == null) {
                return null;
            }
            return new GateReport(
                    value.status().name(),
                    value.datasetId(),
                    value.datasetVersion(),
                    value.configFingerprint(),
                    value.evaluatedAt(),
                    value.cases().stream().map(GateCase::from).toList(),
                    value.errorCode()
            );
        }
    }

    /** 单 Case 的真实硬指标。 */
    public record GateCase(
            String caseId,
            String sourceSha256,
            boolean passed,
            double parseSuccessRate,
            int tokenOverflowCount,
            int invalidSourceSpanCount,
            double sourceAccountingRate,
            int silentTruncationCount,
            List<GateFinding> findings
    ) {
        private static GateCase from(ExtractionGateReport.CaseResult value) {
            return new GateCase(
                    value.caseId(),
                    value.sourceSha256(),
                    value.passed(),
                    value.parseSuccessRate(),
                    value.tokenOverflowCount(),
                    value.invalidSourceSpanCount(),
                    value.sourceAccountingRate(),
                    value.silentTruncationCount(),
                    value.findings().stream().map(GateFinding::from).toList()
            );
        }
    }

    /** Gate 稳定原因码与非正文短说明。 */
    public record GateFinding(String code, String message) {
        private static GateFinding from(ExtractionGateReport.Finding value) {
            return new GateFinding(value.code(), value.message());
        }
    }

    /** 成功文件的 Parser、产物、耗时和分层决策诊断。 */
    public record Diagnostics(
            String parserId,
            String processorVersion,
            int elementCount,
            int chunkCount,
            long parseDurationMs,
            long cleanDurationMs,
            long chunkDurationMs,
            CleaningDiagnostics cleaning,
            ChunkingDiagnostics chunking
    ) {

        /** 非成功 Item 没有完整诊断，因此返回 null 而不是伪造零值对象。 */
        private static Diagnostics from(ItemDiagnostics diagnostics) {
            if (diagnostics == null) {
                return null;
            }
            return new Diagnostics(
                    diagnostics.parserId(),
                    diagnostics.processorVersion(),
                    diagnostics.elementCount(),
                    diagnostics.chunkCount(),
                    diagnostics.parseDurationMillis(),
                    diagnostics.cleanDurationMillis(),
                    diagnostics.chunkDurationMillis(),
                    CleaningDiagnostics.from(diagnostics.cleaning()),
                    ChunkingDiagnostics.from(diagnostics.chunking())
            );
        }
    }

    /** Clean 阶段的可检索、仅治理和稳定原因码统计。 */
    public record CleaningDiagnostics(
            int indexableElements,
            int metadataOnlyElements,
            Map<String, Integer> reasonCodeCounts
    ) {

        private static CleaningDiagnostics from(
                dev.infinityknowledge.spi.extraction.ExtractionRunStore
                        .CleaningDiagnostics diagnostics
        ) {
            return new CleaningDiagnostics(
                    diagnostics.indexableElements(),
                    diagnostics.metadataOnlyElements(),
                    diagnostics.reasonCodeCounts()
            );
        }
    }

    /** Chunk 阶段固定的边界建议、实际决策和大小分布指标。 */
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

        private static ChunkingDiagnostics from(
                dev.infinityknowledge.spi.extraction.ExtractionRunStore
                        .ChunkDiagnostics diagnostics
        ) {
            return new ChunkingDiagnostics(
                    diagnostics.structuralHardBreaks(),
                    diagnostics.baselineSoftBreaks(),
                    diagnostics.semanticCandidateBoundaries(),
                    diagnostics.semanticCutSuggestions(),
                    diagnostics.semanticJoinSuggestions(),
                    diagnostics.semanticNeutralSuggestions(),
                    diagnostics.semanticCutsAdded(),
                    diagnostics.semanticJoinsApplied(),
                    diagnostics.semanticNoOps(),
                    diagnostics.tokenLimitBreaks(),
                    diagnostics.spanLimitBreaks(),
                    diagnostics.rejectedSemanticJoins(),
                    diagnostics.finalChunkCount(),
                    diagnostics.minimumChunkUnits(),
                    diagnostics.averageChunkUnits(),
                    diagnostics.maximumChunkUnits()
            );
        }
    }
}
