package dev.infinityknowledge.ingestion.config;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;

/**
 * 为一次摄取解析 Space 创建时固化的完整处理配置。
 *
 * <p>正式摄取或未覆盖的测试必须使用 {@link #resolve} 并校验当前部署可执行性；
 * 带完整测试覆盖的请求只需要 {@link #resolveStored} 确认 Space 身份和固定版本，
 * 避免已下线的 Space Adapter 阻止一套独立、有效的测试配置。</p>
 */
@FunctionalInterface
public interface SpaceDocumentProcessingConfigResolver {

    /** 返回一次摄取使用的不可变文档处理配置快照。 */
    SpaceDocumentProcessingConfig resolve(TenantId tenantId, KnowledgeSpaceId spaceId);

    /**
     * 只读取 Space 创建时固化的配置，不要求该固定配置在当前部署仍可执行。
     *
     * <p>默认实现保持简单 Adapter 与测试替身的兼容；需要区分能力校验的持久化
     * 实现应覆盖此方法。调用方仍必须校验实际使用的测试覆盖。</p>
     */
    default SpaceDocumentProcessingConfig resolveStored(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        return resolve(tenantId, spaceId);
    }
}
