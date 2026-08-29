package dev.infinityknowledge.controlplane.config.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定检索观测文本指纹的外部密钥和轮换版本。
 *
 * @param secret HMAC 密钥；生产和验收环境必须由秘密管理系统注入
 * @param keyVersion 密钥轮换版本，不包含密钥内容
 */
@ConfigurationProperties(prefix = "infinity.knowledge.retrieval.observation")
public record RetrievalObservationFingerprintProperties(
        String secret,
        String keyVersion
) {
    /** 保持属性本身非空，实际字节强度由加密实现统一校验。 */
    public RetrievalObservationFingerprintProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "retrieval observation fingerprint secret must be configured"
            );
        }
        if (keyVersion == null || keyVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "retrieval observation fingerprint key version must be configured"
            );
        }
    }
}
