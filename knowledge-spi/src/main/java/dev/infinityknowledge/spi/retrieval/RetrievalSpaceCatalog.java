package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Set;

/**
 * 为检索路由加载知识空间名称和用途描述。
 *
 * <p>调用方只能传入授权策略已经允许的空间标识，适配器仍必须按租户和活动状态过滤。</p>
 */
@FunctionalInterface
public interface RetrievalSpaceCatalog {

    /**
     * 加载已授权空间摘要。
     *
     * @param tenantId 当前租户
     * @param allowedSpaceIds 授权后的空间集合
     * @return 空间摘要，执行层负责规范化成稳定顺序
     */
    List<SpaceRoutingCandidate> findAllowed(
            TenantId tenantId,
            Set<KnowledgeSpaceId> allowedSpaceIds
    );
}
