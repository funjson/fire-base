package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * 描述一个空间索引代际实际绑定的外部物理目标。
 *
 * <p>{@code vectorGeneration} 是既有向量集合代际；关键词目标只保存不含地址、正文或
 * 凭据的稳定指纹。未启用外部关键词索引时指纹为空，以保持 PostgreSQL 默认部署原有
 * 代际身份不变。</p>
 */
public record IndexPhysicalContract(
        String vectorGeneration,
        String keywordTargetFingerprint
) {

    /** 校验物理目标标识，禁止把任意长配置或敏感信息带入代际合同。 */
    public IndexPhysicalContract {
        vectorGeneration = DomainChecks.requiredText(
                vectorGeneration,
                "vectorGeneration",
                64
        );
        keywordTargetFingerprint = keywordTargetFingerprint == null
                ? ""
                : keywordTargetFingerprint.strip();
        if (!keywordTargetFingerprint.isEmpty()
                && !keywordTargetFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "keywordTargetFingerprint must be a lowercase SHA-256 fingerprint"
            );
        }
    }

    /** 创建不含外部关键词目标的基线合同。 */
    public static IndexPhysicalContract baseline(String vectorGeneration) {
        return new IndexPhysicalContract(vectorGeneration, "");
    }

    /** 创建同时绑定外部关键词索引的合同。 */
    public static IndexPhysicalContract withKeywordTarget(
            String vectorGeneration,
            String keywordTargetFingerprint
    ) {
        String fingerprint = DomainChecks.requiredText(
                keywordTargetFingerprint,
                "keywordTargetFingerprint",
                64
        );
        return new IndexPhysicalContract(vectorGeneration, fingerprint);
    }

    /** 返回当前合同是否包含外部关键词物理目标。 */
    public boolean hasKeywordTarget() {
        return !keywordTargetFingerprint.isEmpty();
    }
}
