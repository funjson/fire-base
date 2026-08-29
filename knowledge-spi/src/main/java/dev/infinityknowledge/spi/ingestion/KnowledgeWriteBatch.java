package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.document.SourceObjectReference;
import dev.infinityknowledge.spi.connector.ConnectorWriteFence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 表示一次可原子发布的文档修订及其检索投影。
 *
 * @param document 文档治理聚合
 * @param revision 不可变修订
 * @param elements 结构元素
 * @param chunks 检索单元
 * @param sourceObject 可选的原文件对象引用；纯文本 API 写入为 {@code null}
 * @param expectedDocumentProcessingConfigVersion 解析开始时读取的空间处理配置版本，
 *                                         当前必须是创建时固化的版本 1
 * @param expectedDocumentProcessingContractFingerprint 抽取实际绑定的 Space 固化合同指纹
 */
public record KnowledgeWriteBatch(
        KnowledgeDocument document,
        DocumentRevision revision,
        List<KnowledgeElement> elements,
        List<KnowledgeChunk> chunks,
        SourceObjectReference sourceObject,
        ConnectorWriteFence connectorWriteFence,
        long expectedDocumentProcessingConfigVersion,
        String expectedDocumentProcessingContractFingerprint
) {

    /**
     * 校验文档、修订、元素和 Chunk 的归属一致性。
     */
    public KnowledgeWriteBatch {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(revision, "revision must not be null");
        elements = List.copyOf(Objects.requireNonNull(elements, "elements must not be null"));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        if (!document.id().equals(revision.documentId())) {
            throw new IllegalArgumentException("revision does not belong to document");
        }
        if (elements.isEmpty() || chunks.isEmpty()) {
            throw new IllegalArgumentException("write batch must contain elements and chunks");
        }
        if (sourceObject != null && !revision.id().equals(sourceObject.revisionId())) {
            throw new IllegalArgumentException("source object does not belong to revision");
        }
        if (sourceObject != null
                && !revision.contentHash().equals(sourceObject.checksumSha256())) {
            throw new IllegalArgumentException("source object checksum does not match revision");
        }
        if (connectorWriteFence != null
                && !document.tenantId().equals(connectorWriteFence.tenantId())) {
            throw new IllegalArgumentException("connector write fence belongs to another tenant");
        }
        if (expectedDocumentProcessingConfigVersion != 1L) {
            throw new IllegalArgumentException(
                    "expectedDocumentProcessingConfigVersion must be 1"
            );
        }
        Objects.requireNonNull(
                expectedDocumentProcessingContractFingerprint,
                "expectedDocumentProcessingContractFingerprint must not be null"
        );
        if (!expectedDocumentProcessingContractFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "expected processing contract fingerprint must be lowercase SHA-256"
            );
        }
        elements.forEach(element -> {
            if (!revision.id().equals(element.revisionId())) {
                throw new IllegalArgumentException("element does not belong to revision");
            }
        });
        Map<java.util.UUID, KnowledgeElement> elementsById = indexElements(elements);
        chunks.forEach(chunk -> {
            if (!document.tenantId().equals(chunk.tenantId())
                    || !document.spaceId().equals(chunk.spaceId())
                    || !document.id().equals(chunk.documentId())
                    || !revision.id().equals(chunk.revisionId())) {
                throw new IllegalArgumentException("chunk does not belong to write batch");
            }
            validateSourceSpans(chunk, elementsById);
        });
    }

    /**
     * 将来源范围在发布前绑定到本批次的真实 Element，防止错误坐标被持久化后
     * 造成原文高亮越界。
     */
    private static void validateSourceSpans(
            KnowledgeChunk chunk,
            Map<java.util.UUID, KnowledgeElement> elementsById
    ) {
        chunk.sourceSpans().forEach(span -> {
            KnowledgeElement element = elementsById.get(span.elementId());
            if (element == null) {
                throw new IllegalArgumentException("source span element is not in write batch");
            }
            if (span.endOffset() > element.content().length()) {
                throw new IllegalArgumentException("source span exceeds element content");
            }
        });
    }

    private static Map<java.util.UUID, KnowledgeElement> indexElements(
            List<KnowledgeElement> elements
    ) {
        Map<java.util.UUID, KnowledgeElement> indexed = new HashMap<>();
        for (KnowledgeElement element : elements) {
            if (indexed.putIfAbsent(element.id(), element) != null) {
                throw new IllegalArgumentException("write batch contains duplicate element id");
            }
        }
        return Map.copyOf(indexed);
    }
}
