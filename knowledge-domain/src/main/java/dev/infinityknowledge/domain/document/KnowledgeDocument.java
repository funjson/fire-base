package dev.infinityknowledge.domain.document;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 表示跨修订稳定的知识文档治理聚合。
 *
 * @param id 文档标识
 * @param tenantId 所属租户
 * @param spaceId 所属知识空间
 * @param title 当前标题
 * @param source 外部来源
 * @param status 生命周期状态
 * @param authority 权威等级，数值越大越权威
 * @param metadata 受治理的非敏感元数据
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record KnowledgeDocument(
        DocumentId id,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        String title,
        SourceDescriptor source,
        DocumentStatus status,
        int authority,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 校验文档归属、权威等级和时间字段。
     */
    public KnowledgeDocument {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        title = DomainChecks.requiredText(title, "title", 512);
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (authority < 0 || authority > 100) {
            throw new IllegalArgumentException("authority must be between 0 and 100");
        }
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}

