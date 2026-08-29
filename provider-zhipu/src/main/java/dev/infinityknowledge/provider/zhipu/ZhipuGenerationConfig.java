package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 定义结构化 GLM 调用的网络、重试和输入输出预算。
 *
 * @param endpoint 完整 Chat Completions 端点
 * @param apiKey Provider 凭据
 * @param model 模型标识
 * @param requestTimeout 单次网络请求超时
 * @param maxAttempts 包含首次调用的最大尝试次数
 * @param initialBackoff 首次重试等待时间
 * @param maxInputCharacters 单次请求允许发送的最大输入字符数
 * @param maxOutputTokens 模型最大输出 Token 预算
 */
public record ZhipuGenerationConfig(
        URI endpoint,
        String apiKey,
        String model,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        int maxInputCharacters,
        int maxOutputTokens
) {

    /**
     * 校验网络、凭据和请求预算配置。
     */
    public ZhipuGenerationConfig {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint must use HTTP or HTTPS");
        }
        apiKey = DomainChecks.requiredText(apiKey, "apiKey", 4096);
        model = DomainChecks.requiredText(model, "model", 128);
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive and at most five minutes"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        if (initialBackoff.isNegative() || initialBackoff.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException(
                    "initialBackoff must be between zero and ten seconds"
            );
        }
        if (maxInputCharacters < 1_000 || maxInputCharacters > 500_000) {
            throw new IllegalArgumentException(
                    "maxInputCharacters must be between 1000 and 500000"
            );
        }
        if (maxOutputTokens < 256 || maxOutputTokens > 32_768) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be between 256 and 32768"
            );
        }
    }
}
