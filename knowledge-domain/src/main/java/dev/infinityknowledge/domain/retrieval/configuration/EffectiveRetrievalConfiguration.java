package dev.infinityknowledge.domain.retrieval.configuration;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;

/**
 * 表示某次请求最终执行的完整检索配置及其来源修订。
 *
 * @param tenantId 来源配置所属租户
 * @param spaceId 来源配置所属 Space
 * @param sourceRevision Space 当前配置的来源修订
 * @param configuration 合并请求覆盖后的完整配置
 * @param fingerprint 最终有效配置的稳定 SHA-256
 */
public record EffectiveRetrievalConfiguration(
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        long sourceRevision,
        RetrievalConfiguration configuration,
        String fingerprint
) {
    /** 校验来源身份和有效配置指纹。 */
    public EffectiveRetrievalConfiguration {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        if (sourceRevision < 1) {
            throw new IllegalArgumentException("sourceRevision must be positive");
        }
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        if (!configuration.fingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException(
                    "effective retrieval fingerprint does not match configuration"
            );
        }
    }

    /** 使用已合并配置自动计算有效指纹。 */
    public static EffectiveRetrievalConfiguration from(
            SpaceRetrievalConfiguration source,
            RetrievalConfiguration effective
    ) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(effective, "effective must not be null");
        return new EffectiveRetrievalConfiguration(
                source.tenantId(),
                source.spaceId(),
                source.revision(),
                effective,
                effective.fingerprint()
        );
    }
}
