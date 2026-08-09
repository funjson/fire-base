package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 保存智谱 Embedding API 的连接、批量和重试配置。
 *
 * @param endpoint Embedding 完整端点
 * @param apiKey API Key
 * @param requestTimeout 单次请求超时
 * @param maxBatchSize 最大批量文本数
 * @param maxAttempts 包含首次调用的最大尝试次数
 * @param initialBackoff 首次退避时间
 */
public record ZhipuEmbeddingConfig(
        URI endpoint,
        String apiKey,
        Duration requestTimeout,
        int maxBatchSize,
        int maxAttempts,
        Duration initialBackoff
) {

    /**
     * 校验端点、密钥、超时和智谱批量限制。
     */
    public ZhipuEmbeddingConfig {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint must use HTTP or HTTPS");
        }
        apiKey = DomainChecks.requiredText(apiKey, "apiKey", 4096);
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxBatchSize < 1 || maxBatchSize > 64) {
            throw new IllegalArgumentException("maxBatchSize must be between 1 and 64");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        if (initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must not be negative");
        }
    }
}

