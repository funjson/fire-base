package dev.infinityknowledge.spi.model;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.List;
import java.util.Objects;

/**
 * 一次完整模型 Prompt 的 Token 计数请求。
 *
 * <p>模型标识与生成请求同源传入，禁止额外维护可独立修改的 tokenizerModel，
 * 从数据结构上消除“一个模型生成、另一个模型计数”的配置漂移。</p>
 *
 * @param providerId 生成模型 Provider
 * @param modelId 生成请求实际模型
 * @param messages 与生成请求顺序、角色和正文完全一致的消息
 */
public record ModelTokenEstimateRequest(
        String providerId,
        String modelId,
        List<ModelMessage> messages
) {
    private static final int MAXIMUM_MESSAGES = 128;

    /** 校验稳定模型标识和有界消息列表。 */
    public ModelTokenEstimateRequest {
        providerId = DomainChecks.requiredText(providerId, "providerId", 64);
        modelId = DomainChecks.requiredText(modelId, "modelId", 128);
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty() || messages.size() > MAXIMUM_MESSAGES
                || messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "messages must contain between 1 and 128 non-null entries"
            );
        }
        messages = List.copyOf(messages);
    }
}
