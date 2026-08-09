package dev.infinityknowledge.spi.embedding;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * 固定一次索引 generation 使用的向量模型契约。
 *
 * @param providerId Provider 标识
 * @param modelId 模型标识
 * @param dimensions 向量维度
 */
public record EmbeddingSpec(String providerId, String modelId, int dimensions) {

    /**
     * 校验模型标识和向量维度。
     */
    public EmbeddingSpec {
        providerId = DomainChecks.requiredText(providerId, "providerId", 64);
        modelId = DomainChecks.requiredText(modelId, "modelId", 128);
        if (dimensions < 1 || dimensions > 65_536) {
            throw new IllegalArgumentException("dimensions must be between 1 and 65536");
        }
    }
}

