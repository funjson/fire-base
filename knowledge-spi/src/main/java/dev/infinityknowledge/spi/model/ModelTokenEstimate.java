package dev.infinityknowledge.spi.model;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * Prompt Token 计数结果。
 *
 * @param promptTokens 完整消息列表的 Token 数
 * @param exact 是否由目标模型对应的 Tokenizer 精确返回
 * @param estimatorVersion 计数协议与解析合同版本
 */
public record ModelTokenEstimate(
        int promptTokens,
        boolean exact,
        String estimatorVersion
) {
    /** 拒绝零值、负数和不稳定版本，避免把未知值伪装成真实计数。 */
    public ModelTokenEstimate {
        if (promptTokens < 1) {
            throw new IllegalArgumentException("promptTokens must be positive");
        }
        estimatorVersion = DomainChecks.requiredText(
                estimatorVersion,
                "estimatorVersion",
                128
        );
    }
}
