package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 定义智谱远程 Prompt Tokenizer 的网络、重试和输入边界。
 *
 * <p>这里故意不保存模型。模型必须由每次生成请求传入，使生成和计数共享唯一模型来源。</p>
 *
 * @param endpoint 完整 Tokenizer API 端点
 * @param apiKey Provider 凭据
 * @param requestTimeout 单次计数请求超时
 * @param maxAttempts 包含首次调用的最大尝试次数
 * @param initialBackoff 首次重试等待时间
 * @param maximumInputCharacters 完整消息列表允许发送的最大字符数
 */
public record ZhipuTokenizerConfig(
        URI endpoint,
        String apiKey,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        int maximumInputCharacters
) {
    /** 校验 Tokenizer 不会形成无界或长时间阻塞的外部调用。 */
    public ZhipuTokenizerConfig {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint must use HTTP or HTTPS");
        }
        apiKey = DomainChecks.requiredText(apiKey, "apiKey", 4_096);
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive and at most 30 seconds"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 3");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        if (initialBackoff.isNegative()
                || initialBackoff.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException(
                    "initialBackoff must be between zero and five seconds"
            );
        }
        if (maximumInputCharacters < 1_000 || maximumInputCharacters > 500_000) {
            throw new IllegalArgumentException(
                    "maximumInputCharacters must be between 1000 and 500000"
            );
        }
    }

    /** 返回所有请求尝试和指数退避耗尽时的最坏耗时预算。 */
    public Duration maximumLatency() {
        Duration total = requestTimeout.multipliedBy(maxAttempts);
        Duration backoff = initialBackoff;
        for (int retry = 1; retry < maxAttempts; retry++) {
            total = total.plus(backoff);
            backoff = backoff.multipliedBy(2);
        }
        return total;
    }
}
