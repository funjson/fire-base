package dev.infinityknowledge.runtime.ingestion;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceObjectReference;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.chunking.ChunkingDiagnostics;
import dev.infinityknowledge.ingestion.extraction.ExtractionDiagnostics;
import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.spi.connector.ConnectorWriteFence;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * 把已经完成抽取的不可变结果原子发布为文档修订。
 *
 * <p>同步 API、Connector 与异步多文件任务都必须复用本服务。Parser、Cleaner 和
 * Chunker 不在这里重新执行，发布阶段只负责治理聚合、处理诊断、原件引用和事务
 * 写入，避免不同入口复制一套近似的修订组装逻辑。</p>
 */
public final class DocumentPublicationService {

    private final KnowledgeWriter writer;
    private final Clock clock;

    /** 创建不依赖 Spring、HTTP 或具体数据库的发布服务。 */
    public DocumentPublicationService(KnowledgeWriter writer, Clock clock) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 发布一次真实抽取结果。
     *
     * <p>{@code expectedConfigVersion} 是最终事务写栅栏，不参与处理语义指纹；
     * 当前 Space 固化版本只能为 1，处理语义变化必须使用新的 Space。</p>
     */
    public Publication publish(
            PublicationRequest request,
            ExtractionResult extraction,
            long expectedConfigVersion,
            DocumentProcessingContract processingContract
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(extraction, "extraction must not be null");
        if (expectedConfigVersion != 1L) {
            throw new IllegalArgumentException("expectedConfigVersion must be 1");
        }
        Objects.requireNonNull(
                processingContract,
                "processingContract must not be null"
        );
        if (extraction.chunks().isEmpty()) {
            throw new IllegalArgumentException("extraction must contain chunks");
        }
        boolean foreignChunk = extraction.chunks().stream().anyMatch(chunk ->
                !request.documentId().equals(chunk.documentId())
                        || !request.tenantId().equals(chunk.tenantId())
                        || !request.spaceId().equals(chunk.spaceId())
                        || !extraction.revisionId().equals(chunk.revisionId())
        );
        if (foreignChunk) {
            throw new IllegalArgumentException("extraction belongs to another publication");
        }
        if (request.sourceObject() != null
                && !request.sourceObject().revisionId().equals(extraction.revisionId())) {
            throw new IllegalArgumentException("source object belongs to another revision");
        }
        if (request.sourceObject() != null
                && !request.sourceObject().checksumSha256().equals(extraction.sourceSha256())) {
            throw new IllegalArgumentException("source object checksum differs from extraction");
        }

        Instant now = clock.instant();
        Map<String, String> metadata = processingMetadata(
                request.metadata(),
                extraction,
                expectedConfigVersion,
                request.chunkerProviderId(),
                processingContract.fingerprint()
        );
        var document = new KnowledgeDocument(
                request.documentId(),
                request.tenantId(),
                request.spaceId(),
                request.title(),
                request.source(),
                DocumentStatus.ACTIVE,
                request.authority(),
                metadata,
                now,
                now
        );
        var revision = new DocumentRevision(
                extraction.revisionId(),
                request.documentId(),
                extraction.sourceSha256(),
                extraction.mediaType(),
                request.language(),
                extraction.contracts().processorVersion(),
                now
        );
        KnowledgeWriteResult result = writer.write(new KnowledgeWriteBatch(
                document,
                revision,
                extraction.retainedElements(),
                extraction.chunks(),
                request.sourceObject(),
                request.connectorWriteFence(),
                expectedConfigVersion,
                processingContract.fingerprint()
        ));
        return new Publication(
                result,
                extraction.retainedElements().size(),
                extraction.diagnostics().parse().parserId(),
                extraction.diagnostics()
        );
    }

    /** 完整合同和聚合诊断只进入治理元数据，不写入正文、向量或模型响应。 */
    private static Map<String, String> processingMetadata(
            Map<String, String> sourceMetadata,
            ExtractionResult extraction,
            long configVersion,
            String chunkerProviderId,
            String processingContractFingerprint
    ) {
        var contracts = extraction.contracts();
        var diagnostics = extraction.diagnostics();
        var cleaning = diagnostics.cleaning();
        Map<String, String> metadata = new LinkedHashMap<>(sourceMetadata);
        metadata.put("normalizerContract", contracts.normalizer());
        metadata.put("cleanerContract", contracts.cleaner());
        metadata.put("parser", diagnostics.parse().parserId());
        metadata.put("parserContract", contracts.parser());
        metadata.put("chunkerContract", contracts.chunker());
        metadata.put("documentProcessingConfigVersion", Long.toString(configVersion));
        metadata.put(
                "documentProcessingContractFingerprint",
                processingContractFingerprint
        );
        metadata.put("chunkerProviderId", chunkerProviderId);
        metadata.put(
                "cleaningMetadataOnlyElementCount",
                Integer.toString(cleaning.metadataOnlyElements())
        );
        String decisionCounts = cleaningDecisionCounts(cleaning.reasonCodeCounts());
        if (!decisionCounts.isEmpty()) {
            metadata.put("cleaningDecisionCounts", decisionCounts);
        }
        metadata.put(
                "parseDurationNanos",
                Long.toString(diagnostics.parse().duration().toNanos())
        );
        metadata.put(
                "cleaningDurationNanos",
                Long.toString(diagnostics.cleaning().duration().toNanos())
        );
        metadata.put(
                "chunkingDurationNanos",
                Long.toString(diagnostics.chunking().duration().toNanos())
        );
        addChunkingDiagnostics(metadata, diagnostics.chunking().diagnostics());
        metadata.put("processingPipeline", contracts.pipeline());
        return Map.copyOf(metadata);
    }

    /** 写入固定的 Chunk 诊断字段，保证后续评测可以跨修订比较。 */
    private static void addChunkingDiagnostics(
            Map<String, String> metadata,
            ChunkingDiagnostics diagnostics
    ) {
        metadata.put("chunkingStructuralHardBreaks",
                Integer.toString(diagnostics.structuralHardBreaks()));
        metadata.put("chunkingBaselineSoftBreaks",
                Integer.toString(diagnostics.baselineSoftBreaks()));
        metadata.put("chunkingSemanticCandidateBoundaries",
                Integer.toString(diagnostics.semanticCandidateBoundaries()));
        metadata.put("chunkingSemanticCutSuggestions",
                Integer.toString(diagnostics.semanticCutSuggestions()));
        metadata.put("chunkingSemanticJoinSuggestions",
                Integer.toString(diagnostics.semanticJoinSuggestions()));
        metadata.put("chunkingSemanticNeutralSuggestions",
                Integer.toString(diagnostics.semanticNeutralSuggestions()));
        metadata.put("chunkingSemanticCutsAdded",
                Integer.toString(diagnostics.semanticCutsAdded()));
        metadata.put("chunkingSemanticJoinsApplied",
                Integer.toString(diagnostics.semanticJoinsApplied()));
        metadata.put("chunkingSemanticNoOps",
                Integer.toString(diagnostics.semanticNoOps()));
        metadata.put("chunkingTokenLimitBreaks",
                Integer.toString(diagnostics.tokenLimitBreaks()));
        metadata.put("chunkingSpanLimitBreaks",
                Integer.toString(diagnostics.spanLimitBreaks()));
        metadata.put("chunkingRejectedSemanticJoins",
                Integer.toString(diagnostics.rejectedSemanticJoins()));
        metadata.put("chunkingFinalChunkCount",
                Integer.toString(diagnostics.finalChunkCount()));
        metadata.put("chunkingMinimumUnits",
                Integer.toString(diagnostics.minimumChunkUnits()));
        metadata.put("chunkingAverageUnits",
                Double.toString(diagnostics.averageChunkUnits()));
        metadata.put("chunkingMaximumUnits",
                Integer.toString(diagnostics.maximumChunkUnits()));
    }

    /** 清洗决策只保存稳定原因码和数量。 */
    private static String cleaningDecisionCounts(Map<String, Integer> counts) {
        StringJoiner summary = new StringJoiner(",");
        new TreeMap<>(counts).forEach((reasonCode, count) ->
                summary.add(reasonCode + "=" + count)
        );
        return summary.toString();
    }

    /**
     * 已完成来源识别和抽取的发布上下文。
     *
     * <p>该对象代表一个真实用例输入，而不是为了复用校验而创建的包装。来源描述、
     * 治理属性和原件引用在发布阶段必须作为一个不可变快照共同生效。</p>
     */
    public record PublicationRequest(
            dev.infinityknowledge.domain.document.DocumentId documentId,
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SourceDescriptor source,
            String title,
            String language,
            int authority,
            Map<String, String> metadata,
            String chunkerProviderId,
            SourceObjectReference sourceObject,
            ConnectorWriteFence connectorWriteFence
    ) {

        /** 校验治理字段和租户边界，不重复 Parser/Chunker 的输入校验。 */
        public PublicationRequest {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            Objects.requireNonNull(source, "source must not be null");
            title = DomainChecks.requiredText(title, "title", 512);
            language = DomainChecks.requiredText(language, "language", 32);
            if (authority < 0 || authority > 100) {
                throw new IllegalArgumentException("authority must be between 0 and 100");
            }
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
            chunkerProviderId = DomainChecks.requiredText(
                    chunkerProviderId,
                    "chunkerProviderId",
                    64
            );
            if (connectorWriteFence != null
                    && !tenantId.equals(connectorWriteFence.tenantId())) {
                throw new IllegalArgumentException(
                        "connector write fence belongs to another tenant"
                );
            }
        }
    }

    /** 发布结果与不含敏感正文的抽取诊断。 */
    public record Publication(
            KnowledgeWriteResult writeResult,
            int elementCount,
            String parserId,
            ExtractionDiagnostics extractionDiagnostics
    ) {

        /** 保存事务结果和分阶段诊断。 */
        public Publication {
            Objects.requireNonNull(writeResult, "writeResult must not be null");
            if (elementCount < 0) {
                throw new IllegalArgumentException("elementCount must be non-negative");
            }
            parserId = DomainChecks.requiredText(parserId, "parserId", 128);
            Objects.requireNonNull(
                    extractionDiagnostics,
                    "extractionDiagnostics must not be null"
            );
        }
    }
}
