package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.KnowledgeElement;

import java.util.List;
import java.util.Objects;

/**
 * 表示一次可原子发布的文档修订及其检索投影。
 *
 * @param document 文档治理聚合
 * @param revision 不可变修订
 * @param elements 结构元素
 * @param chunks 检索单元
 */
public record KnowledgeWriteBatch(
        KnowledgeDocument document,
        DocumentRevision revision,
        List<KnowledgeElement> elements,
        List<KnowledgeChunk> chunks
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
