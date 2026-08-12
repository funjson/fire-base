package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.document.SourceObjectReference;
import dev.infinityknowledge.spi.connector.ConnectorWriteFence;

import java.util.List;
import java.util.Objects;

/**
 * 表示一次可原子发布的文档修订及其检索投影。
 *
 * @param document 文档治理聚合
 * @param revision 不可变修订
 * @param elements 结构元素
 * @param chunks 检索单元
 * @param sourceObject 可选的原文件对象引用；纯文本 API 写入为 {@code null}
 */
public record KnowledgeWriteBatch(
        KnowledgeDocument document,
        DocumentRevision revision,
        List<KnowledgeElement> elements,
        List<KnowledgeChunk> chunks,
        SourceObjectReference sourceObject,
        ConnectorWriteFence connectorWriteFence
) {

    /** Preserves the original-source ingestion constructor. */
    public KnowledgeWriteBatch(
            KnowledgeDocument document,
            DocumentRevision revision,
            List<KnowledgeElement> elements,
            List<KnowledgeChunk> chunks,
            SourceObjectReference sourceObject
    ) {
        this(document, revision, elements, chunks, sourceObject, null);
    }

    /** Preserves the original text-only ingestion constructor. */
    public KnowledgeWriteBatch(
            KnowledgeDocument document,
            DocumentRevision revision,
            List<KnowledgeElement> elements,
            List<KnowledgeChunk> chunks
    ) {
        this(document, revision, elements, chunks, null, null);
    }

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
        elements.forEach(element -> {
            if (!revision.id().equals(element.revisionId())) {
                throw new IllegalArgumentException("element does not belong to revision");
            }
        });
        chunks.forEach(chunk -> {
            if (!document.tenantId().equals(chunk.tenantId())
                    || !document.spaceId().equals(chunk.spaceId())
                    || !document.id().equals(chunk.documentId())
                    || !revision.id().equals(chunk.revisionId())) {
                throw new IllegalArgumentException("chunk does not belong to write batch");
            }
        });
    }
}
