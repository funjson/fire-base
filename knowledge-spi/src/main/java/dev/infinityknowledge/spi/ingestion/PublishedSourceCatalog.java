package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 查询正式上传入口已经发布的来源身份和活动内容指纹。
 *
 * <p>该端口只服务“创建且不覆盖”的多文件摄取语义：外部键冲突与空间内内容重复
 * 必须在昂贵解析前确定。它不承担通用文档搜索，也不把 JDBC 或表结构泄漏给 Runtime。</p>
 */
public interface PublishedSourceCatalog {

    /** 按空间、来源连接器和外部稳定键查询现有逻辑文档。 */
    Optional<PublishedSource> findByExternalId(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String connectorId,
            String externalId
    );

    /** 按空间和原件 SHA-256 查询已经激活的同内容文档。 */
    Optional<PublishedSource> findByContentHash(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String contentHash
    );

    /**
     * 一份已发布来源的最小身份快照。
     *
     * @param documentId 稳定文档标识
     * @param revisionId 当前活动修订
     * @param contentHash 当前活动原件 SHA-256
     */
    record PublishedSource(
            DocumentId documentId,
            UUID revisionId,
            String contentHash
    ) {

        /** 保存可用于幂等返回的完整活动修订身份。 */
        public PublishedSource {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(revisionId, "revisionId must not be null");
            Objects.requireNonNull(contentHash, "contentHash must not be null");
            if (!contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "contentHash must be a lowercase SHA-256"
                );
            }
        }
    }
}
