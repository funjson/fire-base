package dev.infinityknowledge.spi.retrieval.configuration;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.configuration.SpaceRetrievalConfiguration;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Optional;

/**
 * 保存 Space 不可变检索配置修订及其当前修订指针。
 *
 * <p>端口只表达追加修订和原子切换，不暴露草稿、发布或通用 CRUD 状态。
 * 调用方使用期望当前修订避免并发覆盖；零表示该 Space 尚无检索配置。</p>
 */
public interface SpaceRetrievalConfigurationStore {

    /**
     * 读取 Space 当前生效的完整检索配置。
     *
     * @param tenantId 租户标识
     * @param spaceId Space 标识
     * @return 当前修订，不存在时为空
     */
    Optional<SpaceRetrievalConfiguration> findCurrent(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    );

    /**
     * 按精确修订号读取历史配置。
     *
     * @param tenantId 租户标识
     * @param spaceId Space 标识
     * @param revision 正修订号
     * @return 对应不可变修订，不存在时为空
     */
    Optional<SpaceRetrievalConfiguration> findRevision(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            long revision
    );

    /**
     * 按修订号倒序读取有限历史。
     *
     * @param tenantId 租户标识
     * @param spaceId Space 标识
     * @param limit 返回数量，范围 1..200
     * @return 不可变修订列表
     */
    List<SpaceRetrievalConfiguration> history(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            int limit
    );

    /**
     * 追加配置修订并在同一事务中把 Space 当前指针切换到该修订。
     *
     * @param configuration 新的完整不可变修订
     * @param expectedCurrentRevision 调用方读取到的当前修订；首次创建时为零
     * @return 原子切换结果
     */
    ActivationOutcome appendAndActivate(
            SpaceRetrievalConfiguration configuration,
            long expectedCurrentRevision
    );

    /**
     * 表示追加和指针切换的有限结果，不是持久化生命周期状态。
     */
    enum ActivationOutcome {
        /** 新修订和当前指针已经原子提交。 */
        ACTIVATED,
        /** 相同修订和指纹已经是当前值，用于安全重试。 */
        ALREADY_ACTIVE,
        /** 期望修订已过期或同一修订存在不同内容。 */
        REVISION_CONFLICT
    }
}
