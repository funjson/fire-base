package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Optional;

/**
 * 为检索链读取当前活动索引代际；具体来源可以替换为数据库或外部索引控制面。
 */
public interface ActiveIndexGenerationCatalog {

    /**
     * 查找租户 Space 当前唯一的活动索引代际。
     *
     * @param tenantId 租户
     * @param spaceId Space
     * @return 活动代际；尚未完成首次投影时为空
     */
    Optional<ActiveIndexGeneration> findActiveGeneration(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    );
}
