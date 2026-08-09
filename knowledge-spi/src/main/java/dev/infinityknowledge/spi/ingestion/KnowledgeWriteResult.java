package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;

import java.util.Objects;
import java.util.UUID;

/**
 * 表示持久化与活动修订切换的结果。
 *
 * @param documentId 文档标识
 * @param revisionId 活动修订标识
 * @param changed 是否改变活动修订、生命周期或需投影的文档字段
 * @param chunkCount 活动修订 Chunk 数
 */
public record KnowledgeWriteResult(
        DocumentId documentId,
        UUID revisionId,
        boolean changed,
        int chunkCount
) {

    /**
     * 校验写入结果。
     */
    public KnowledgeWriteResult {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must be non-negative");
        }
    }
}
