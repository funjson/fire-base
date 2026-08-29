package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.spi.indexing.ProjectionType;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 表示持久化与活动修订切换的结果。
 *
 * @param documentId 文档标识
 * @param revisionId 活动修订标识
 * @param changed 是否改变活动修订、生命周期或需投影的文档字段
 * @param chunkCount 活动修订 Chunk 数
 * @param sourceObjectAccepted 本次提交的原始对象是否成为该修订的权威原件
 * @param configuredProjectionTypes Writer 已配置且能够事务排队的投影类型
 */
public record KnowledgeWriteResult(
        DocumentId documentId,
        UUID revisionId,
        boolean changed,
        int chunkCount,
        boolean sourceObjectAccepted,
        Set<ProjectionType> configuredProjectionTypes
) {

    /**
     * 保留不带原件和外部投影的轻量写入结果构造器。
     */
    public KnowledgeWriteResult(
            DocumentId documentId,
            UUID revisionId,
            boolean changed,
            int chunkCount
    ) {
        this(documentId, revisionId, changed, chunkCount, false, Set.of());
    }

    /**
     * 保留原件写入结果的兼容构造器。
     */
    public KnowledgeWriteResult(
            DocumentId documentId,
            UUID revisionId,
            boolean changed,
            int chunkCount,
            boolean sourceObjectAccepted
    ) {
        this(
                documentId,
                revisionId,
                changed,
                chunkCount,
                sourceObjectAccepted,
                Set.of()
        );
    }

    /**
     * 校验写入结果。
     */
    public KnowledgeWriteResult {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must be non-negative");
        }
        configuredProjectionTypes = Set.copyOf(Objects.requireNonNull(
                configuredProjectionTypes,
                "configuredProjectionTypes must not be null"
        ));
    }
}
