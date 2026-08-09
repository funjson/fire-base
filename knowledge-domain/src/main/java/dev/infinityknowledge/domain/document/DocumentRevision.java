package dev.infinityknowledge.domain.document;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示一次不可变的规范化文档修订。
 *
 * @param id 修订标识
 * @param documentId 所属文档
 * @param contentHash 规范化内容 SHA-256
 * @param mediaType 内容媒体类型
 * @param language BCP 47 语言标签
 * @param parserVersion 处理契约版本（Parser、Chunker 与预算）
 * @param createdAt 创建时间
 */
public record DocumentRevision(
        UUID id,
        DocumentId documentId,
        String contentHash,
        String mediaType,
        String language,
        String parserVersion,
        Instant createdAt
) {

    /**
     * 校验修订指纹和处理契约版本。
     */
    public DocumentRevision {
        Objects.requireNonNull(id, "revision id must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        contentHash = DomainChecks.requiredText(contentHash, "contentHash", 128);
        mediaType = DomainChecks.requiredText(mediaType, "mediaType", 128);
        language = DomainChecks.requiredText(language, "language", 32);
        parserVersion = DomainChecks.requiredText(parserVersion, "parserVersion", 64);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
