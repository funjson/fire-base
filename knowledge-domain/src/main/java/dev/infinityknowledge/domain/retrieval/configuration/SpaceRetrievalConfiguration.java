package dev.infinityknowledge.domain.retrieval.configuration;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示一个 Space 已物化且不可修改的检索配置修订。
 *
 * <p>修改配置会追加新修订并切换 Space 当前修订指针，历史修订不会被覆盖。
 * 配置指纹只覆盖实际检索语义，修订号和审计字段单独保留。</p>
 *
 * @param tenantId 配置所属租户
 * @param spaceId 配置所属 Space
 * @param revision 从一开始单调递增的修订号
 * @param configuration 完整检索配置
 * @param fingerprint 完整配置的稳定 SHA-256
 * @param createdBy 创建该修订的主体
 * @param createdAt 创建时间
 */
public record SpaceRetrievalConfiguration(
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        long revision,
        RetrievalConfiguration configuration,
        String fingerprint,
        PrincipalId createdBy,
        Instant createdAt
) {
    /** 校验修订身份、审计字段和配置指纹。 */
    public SpaceRetrievalConfiguration {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        if (revision < 1) {
            throw new IllegalArgumentException("retrieval configuration revision must be positive");
        }
        Objects.requireNonNull(configuration, "configuration must not be null");
        fingerprint = requireFingerprint(fingerprint);
        if (!configuration.fingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException(
                    "retrieval configuration fingerprint does not match configuration"
            );
        }
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * 使用完整配置自动计算指纹并创建一个不可变修订。
     */
    public static SpaceRetrievalConfiguration create(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            long revision,
            RetrievalConfiguration configuration,
            PrincipalId createdBy,
            Instant createdAt
    ) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        return new SpaceRetrievalConfiguration(
                tenantId,
                spaceId,
                revision,
                configuration,
                configuration.fingerprint(),
                createdBy,
                createdAt
        );
    }

    private static String requireFingerprint(String value) {
        Objects.requireNonNull(value, "fingerprint must not be null");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256");
        }
        return value;
    }
}
