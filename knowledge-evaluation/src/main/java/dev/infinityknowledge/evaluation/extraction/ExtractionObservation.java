package dev.infinityknowledge.evaluation.extraction;

import dev.infinityknowledge.domain.document.ElementType;

import java.util.List;
import java.util.Objects;

/**
 * 一次抽取运行归一化后的非敏感观测结果。
 *
 * <p>具体 Parser 必须先把厂商输出转换为平台规范化 Artifact 与 typed Provenance；
 * 验收器不依赖 Docling、PDFBox 或厂商 Tokenizer 对象，也不暴露正文或模型原始响应。</p>
 *
 * @param caseId 对应 Golden Case 标识
 * @param sourceSha256 本次观测实际使用的 Source 原始字节 SHA-256
 * @param artifactSha256 本次结果的规范化文本制品 SHA-256
 * @param artifactContract 本次结果的规范化文本制品合同
 * @param artifactLength 本次结果的规范化文本 UTF-16 长度
 * @param processingContracts 本次观测绑定的完整处理合同
 * @param parseSucceeded Parser 是否成功完成
 * @param elements 解析和清洗后的元素观测
 * @param chunks 最终 Chunk 观测
 * @param removedRanges Cleaner 明确删除的源范围
 * @param reportedTruncationRanges 运行时已明确报告的截断范围
 */
public record ExtractionObservation(
        String caseId,
        String sourceSha256,
        String artifactSha256,
        String artifactContract,
        int artifactLength,
        ProcessingContracts processingContracts,
        boolean parseSucceeded,
        List<Element> elements,
        List<Chunk> chunks,
        List<SourceRange> removedRanges,
        List<SourceRange> reportedTruncationRanges
) {

    /** 防御性复制运行结果；非法 SourceSpan 留给验收器生成可读报告。 */
    public ExtractionObservation {
        caseId = required(caseId, "caseId");
        sourceSha256 = sha256(sourceSha256);
        artifactSha256 = sha256(artifactSha256);
        artifactContract = required(artifactContract, "artifactContract");
        if (artifactLength < 0) {
            throw new IllegalArgumentException("artifactLength must be non-negative");
        }
        Objects.requireNonNull(
                processingContracts,
                "processingContracts must not be null"
        );
        elements = immutable(elements, "elements");
        chunks = immutable(chunks, "chunks");
        removedRanges = immutable(removedRanges, "removedRanges");
        reportedTruncationRanges = immutable(
                reportedTruncationRanges,
                "reportedTruncationRanges"
        );
    }

    /**
     * 生成观测结果时实际生效的处理合同快照。
     *
     * <p>只有 {@link ExtractionObservationFactory} 可以从真实 Pipeline 结果和实际
     * TokenCounter 生成发布门禁观测；人工指标 Fixture 不得冒充生产合同。</p>
     *
     * @param processorVersion 完整处理指纹
     * @param normalizerContract 规范化合同
     * @param parserContract Parser 合同
     * @param cleanerContract Cleaner 合同
     * @param chunkerContract Chunker 合同
     * @param tokenizerContract Tokenizer 合同
     */
    public record ProcessingContracts(
            String processorVersion,
            String normalizerContract,
            String parserContract,
            String cleanerContract,
            String chunkerContract,
            String tokenizerContract
    ) {
        /** 所有合同都必须显式存在，避免报告脱离处理语义。 */
        public ProcessingContracts {
            processorVersion = required(processorVersion, "processorVersion");
            normalizerContract = required(normalizerContract, "normalizerContract");
            parserContract = required(parserContract, "parserContract");
            cleanerContract = required(cleanerContract, "cleanerContract");
            chunkerContract = required(chunkerContract, "chunkerContract");
            tokenizerContract = required(tokenizerContract, "tokenizerContract");
        }
    }

    /** 元素在清洗后的用途。 */
    public enum Disposition {
        /** 进入 Chunk 和检索索引。 */
        INDEXABLE,
        /** 仅保留为文档元数据，不进入检索正文。 */
        METADATA_ONLY,
        /** 已删除正文，只保留结构类型、角色、原因和范围诊断。 */
        REMOVED
    }

    /**
     * 元素观测。
     *
     * @param sourceRange 元素对应的规范化 Artifact 范围
     * @param type 元素类型
     * @param role 可选业务角色
     * @param disposition 清洗后的用途
     */
    public record Element(
            SourceRange sourceRange,
            ElementType type,
            String role,
            Disposition disposition
    ) {
        /** 结构字段必须存在，范围合法性由 Runner 结合源文件长度判断。 */
        public Element {
            Objects.requireNonNull(sourceRange, "element sourceRange must not be null");
            Objects.requireNonNull(type, "element type must not be null");
            Objects.requireNonNull(disposition, "element disposition must not be null");
        }
    }

    /**
     * Chunk 观测。
     *
     * @param ordinal 文档内顺序
     * @param sourceSpans Chunk 覆盖的规范化 Artifact 范围
     * @param tokenCount Tokenizer 返回的计数
     * @param tokenLimit 当前处理配置的硬上限
     */
    public record Chunk(
            int ordinal,
            List<SourceRange> sourceSpans,
            int tokenCount,
            int tokenLimit
    ) {
        /** 数量字段允许保留异常值，使 Runner 能将其转成门禁失败而不是反序列化失败。 */
        public Chunk {
            sourceSpans = immutable(sourceSpans, "chunk sourceSpans");
        }
    }

    private static <T> List<T> immutable(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name + " must not be null"));
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        String normalized = required(value, "sourceSha256");
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "sourceSha256 must be a lowercase SHA-256"
            );
        }
        return normalized;
    }
}
