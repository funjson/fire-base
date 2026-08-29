package dev.infinityknowledge.domain.document;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 表示参与关键词、向量和重排检索的不可变索引单元。
 *
 * @param id Chunk 标识
 * @param tenantId 所属租户
 * @param spaceId 所属知识空间
 * @param documentId 所属文档
 * @param revisionId 所属修订
 * @param elementIds 来源元素
 * @param sourceSpans 来源元素内的精确文本范围
 * @param ordinal 文档内稳定顺序
 * @param sectionPath 章节路径
 * @param content 用于展示、关键词检索和引用的原始正文
 * @param contextualText 用于向量化和语义重排的上下文化正文
 * @param contentHash 正文 SHA-256
 * @param metadata 用于过滤和展示的非敏感元数据
 */
public record KnowledgeChunk(
        UUID id,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        DocumentId documentId,
        UUID revisionId,
        List<UUID> elementIds,
        List<ChunkSourceSpan> sourceSpans,
        int ordinal,
        List<String> sectionPath,
        String content,
        String contextualText,
        String contentHash,
        Map<String, String> metadata
) {

    /**
     * 校验 Chunk 的租户归属、来源元素和正文指纹。
     */
    public KnowledgeChunk {
        Objects.requireNonNull(id, "chunk id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        elementIds = List.copyOf(Objects.requireNonNull(elementIds, "elementIds must not be null"));
        if (elementIds.isEmpty()) {
            throw new IllegalArgumentException("chunk must reference at least one element");
        }
        sourceSpans = List.copyOf(Objects.requireNonNull(
                sourceSpans,
                "sourceSpans must not be null"
        ));
        if (sourceSpans.isEmpty() || sourceSpans.size() > 128) {
            throw new IllegalArgumentException("sourceSpans size must be between 1 and 128");
        }
        Set<UUID> declaredElements = Set.copyOf(elementIds);
        if (declaredElements.size() != elementIds.size()) {
            throw new IllegalArgumentException("elementIds must not contain duplicates");
        }
        Set<UUID> referencedElements = sourceSpans.stream()
                .map(ChunkSourceSpan::elementId)
                .collect(Collectors.toUnmodifiableSet());
        if (!referencedElements.equals(declaredElements)) {
            throw new IllegalArgumentException(
                    "source spans must reference exactly the declared chunk elements"
            );
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
        sectionPath = List.copyOf(
                Objects.requireNonNull(sectionPath, "sectionPath must not be null")
        );
        content = DomainChecks.requiredVerbatimText(content, "chunk content", 100_000);
        contextualText = DomainChecks.requiredVerbatimText(
                contextualText,
                "chunk contextualText",
                110_000
        );
        contentHash = DomainChecks.requiredText(contentHash, "contentHash", 128);
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
