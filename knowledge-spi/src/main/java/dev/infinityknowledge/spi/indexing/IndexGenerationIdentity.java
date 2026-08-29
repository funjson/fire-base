package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 统一计算索引代际的执行合同指纹。
 *
 * <p>投影写入与检索前校验必须调用同一实现；任何一侧自行拼接都会造成“查询了新集合、
 * 却上报旧活动代际”的不可观测漂移。指纹只包含稳定合同标识，不包含正文或凭据。</p>
 */
public final class IndexGenerationIdentity {

    private IndexGenerationIdentity() {
    }

    /**
     * 计算 Embedding、物理索引目标、规范化与 Chunk 合同的 SHA-256 指纹。
     *
     * @param embeddingSpec Embedding 执行合同
     * @param physicalContract 外部索引物理目标合同
     * @param normalizerVersion 规范化合同版本
     * @param chunkerVersion Chunk 合同版本
     * @return 64 位小写十六进制指纹
     */
    public static String configurationVersion(
            EmbeddingSpec embeddingSpec,
            IndexPhysicalContract physicalContract,
            String normalizerVersion,
            String chunkerVersion
    ) {
        Objects.requireNonNull(embeddingSpec, "embeddingSpec must not be null");
        Objects.requireNonNull(physicalContract, "physicalContract must not be null");
        String value = String.join(
                "\u001F",
                embeddingSpec.providerId(),
                embeddingSpec.modelId(),
                Integer.toString(embeddingSpec.dimensions()),
                physicalContract.vectorGeneration(),
                DomainChecks.requiredText(
                        normalizerVersion,
                        "normalizerVersion",
                        64
                ),
                DomainChecks.requiredText(
                        chunkerVersion,
                        "chunkerVersion",
                        64
                )
        );
        // 无外部关键词目标时保持原编码，避免默认 PostgreSQL 部署被动生成新代际。
        if (physicalContract.hasKeywordTarget()) {
            value = String.join(
                    "\u001F",
                    value,
                    "keyword",
                    physicalContract.keywordTargetFingerprint()
            );
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
