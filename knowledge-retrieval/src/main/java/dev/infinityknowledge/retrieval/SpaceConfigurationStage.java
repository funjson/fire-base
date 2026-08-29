package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.configuration.EffectiveRetrievalConfiguration;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationHardLimits;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationResolver;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.configuration.SpaceRetrievalConfigurationStore;

import java.util.Objects;

/**
 * 从 Space 当前不可变修订解析一次请求真正执行的检索配置。
 *
 * <p>配置缺失表示 Space 物化不完整，必须显式失败；运行时不会偷偷读取会漂移的系统默认值。</p>
 */
final class SpaceConfigurationStage {
    private final SpaceRetrievalConfigurationStore store;
    private final RetrievalConfigurationResolver resolver;
    private final RetrievalConfigurationHardLimits hardLimits;

    SpaceConfigurationStage(
            SpaceRetrievalConfigurationStore store,
            RetrievalConfigurationResolver resolver,
            RetrievalConfigurationHardLimits hardLimits
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.hardLimits = Objects.requireNonNull(hardLimits, "hardLimits must not be null");
    }

    /** 读取当前修订、应用请求覆盖并再次执行系统硬上限校验。 */
    EffectiveRetrievalConfiguration resolve(
            KnowledgeQuery query,
            KnowledgeSpaceId spaceId
    ) {
        var source = store.findCurrent(query.principal().tenantId(), spaceId)
                .orElseThrow(() -> new MissingSpaceRetrievalConfigurationException(
                        "Space retrieval configuration is not materialized"
                ));
        return resolver.resolve(source, query.configurationOverride(), hardLimits);
    }
}
