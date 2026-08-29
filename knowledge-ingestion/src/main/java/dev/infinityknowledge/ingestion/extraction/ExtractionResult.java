package dev.infinityknowledge.ingestion.extraction;

import dev.infinityknowledge.domain.document.ElementProvenance;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.document.NormalizedDocumentArtifact;
import dev.infinityknowledge.ingestion.cleaning.ElementCleaningDecision;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次无存储副作用的数据抽取结果。
 *
 * <p>{@code retainedElements} 同时包含可索引元素与仅元数据元素，并保持原始阅读
 * 顺序；Chunker 只消费可索引元素。生产发布、配置试验和重处理必须复用这一结果
 * 语义，区别只发生在后续 Write 阶段。</p>
 *
 * @param revisionId 由内容和完整处理契约确定的修订标识
 * @param sourceSha256 本次真实输入原始字节的 SHA-256
 * @param mediaType Parser 选择后的规范媒体类型
 * @param artifact Parser 产生且供所有范围共用的规范化文本制品
 * @param elementProvenance 包含已保留和已删除 Element 的完整来源范围
 * @param cleaningDecisions 包含已保留和已删除 Element 的无正文清洗去向
 * @param retainedElements 应进入结构化存储的有序元素
 * @param chunks 应进入检索投影的有序 Chunk
 * @param contracts 本次实际处理契约
 * @param diagnostics 分阶段非敏感诊断
 */
public record ExtractionResult(
        UUID revisionId,
        String sourceSha256,
        String mediaType,
        NormalizedDocumentArtifact artifact,
        List<ElementProvenance> elementProvenance,
        List<ElementCleaningDecision> cleaningDecisions,
        List<KnowledgeElement> retainedElements,
        List<KnowledgeChunk> chunks,
        ExtractionContracts contracts,
        ExtractionDiagnostics diagnostics
) {

    /** 防御性保存抽取产物；元素和 Chunk 的领域不变量由各自类型负责。 */
    public ExtractionResult {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(sourceSha256, "sourceSha256 must not be null");
        if (!sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be a lowercase SHA-256");
        }
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        Objects.requireNonNull(artifact, "artifact must not be null");
        elementProvenance = List.copyOf(Objects.requireNonNull(
                elementProvenance,
                "elementProvenance must not be null"
        ));
        cleaningDecisions = List.copyOf(Objects.requireNonNull(
                cleaningDecisions,
                "cleaningDecisions must not be null"
        ));
        retainedElements = List.copyOf(Objects.requireNonNull(
                retainedElements,
                "retainedElements must not be null"
        ));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        Objects.requireNonNull(contracts, "contracts must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }
}
