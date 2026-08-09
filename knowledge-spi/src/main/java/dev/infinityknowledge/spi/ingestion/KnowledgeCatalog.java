package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Optional;

/**
 * 提供创建新修订所需的轻量目录查询。
 */
public interface KnowledgeCatalog {

    /**
     * 按完整来源身份查找稳定文档标识。
     *
     * <p>空间是文档身份的一部分，避免不同知识空间中相同 externalId 相互覆盖。</p>
     *
     * @param tenantId 租户
     * @param spaceId 知识空间
     * @param connectorId 连接器
     * @param externalId 来源侧稳定标识
     * @return 已有文档标识，不存在时为空
     */
    Optional<DocumentId> findDocumentId(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String connectorId,
            String externalId
    );
}
