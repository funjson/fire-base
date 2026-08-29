package dev.infinityknowledge.controlplane.config.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 定义 Coverage Judge 的模型端点、字符预算和双层超时。
 */
@ConfigurationProperties(prefix = "infinity.knowledge.retrieval.coverage-judge")
public record CoverageJudgeProperties(
        boolean enabled,
        Duration stageTimeout,
        URI endpoint,
        String apiKey,
        String model,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        int maxInputCharacters,
        int maximumPromptTokens,
        int maxOutputTokens,
        String proxyHost,
        int proxyPort
) {
    /** 补齐保守默认值并禁止无界模型输入。 */
    public CoverageJudgeProperties {
        stageTimeout = stageTimeout == null ? Duration.ofSeconds(10) : stageTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(6) : requestTimeout;
        if (stageTimeout.isZero() || stageTimeout.isNegative()
                || stageTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException(
                    "coverage judge stageTimeout must be positive and at most 60 seconds"
            );
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(stageTimeout) > 0) {
            throw new IllegalArgumentException(
                    "coverage judge requestTimeout must not exceed stageTimeout"
            );
        }
        endpoint = endpoint == null
                ? URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions")
                : endpoint;
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? "glm-5.2" : model.strip();
        maxAttempts = maxAttempts < 1 ? 1 : maxAttempts;
        if (maxAttempts > 2) {
            throw new IllegalArgumentException("coverage judge maxAttempts must not exceed 2");
        }
        initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        maxInputCharacters = maxInputCharacters < 1 ? 120_000 : maxInputCharacters;
        if (maxInputCharacters > 500_000) {
            throw new IllegalArgumentException(
                    "coverage judge maxInputCharacters must not exceed 500000"
            );
        }
        maximumPromptTokens = maximumPromptTokens < 1 ? 65_536 : maximumPromptTokens;
        if (maximumPromptTokens > 500_000) {
            throw new IllegalArgumentException(
                    "coverage judge maximumPromptTokens must not exceed 500000"
            );
        }
        maxOutputTokens = maxOutputTokens < 1 ? 2_048 : maxOutputTokens;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (!proxyHost.isEmpty() && (proxyPort < 1 || proxyPort > 65_535)) {
            throw new IllegalArgumentException(
                    "coverage judge proxyPort must be valid when proxyHost is configured"
            );
        }
    }
}
