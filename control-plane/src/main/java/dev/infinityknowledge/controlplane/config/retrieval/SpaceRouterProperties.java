package dev.infinityknowledge.controlplane.config.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 定义模型空间排序的超时、网络和输入预算。
 *
 * @param enabled 是否启用模型排序
 * @param stageTimeout 执行层等待模型的硬超时
 * @param endpoint 智谱 Chat Completions 端点
 * @param apiKey 智谱 API Key
 * @param model 路由模型
 * @param requestTimeout 单次 HTTP 请求超时
 * @param maxAttempts 包含首次调用的最大尝试次数
 * @param initialBackoff 首次重试等待时间
 * @param maxInputCharacters 单次输入字符预算
 * @param maximumPromptTokens 完整模型消息 Prompt Token 硬预算
 * @param maxOutputTokens 单次输出 Token 预算
 * @param proxyHost 可选代理主机
 * @param proxyPort 可选代理端口
 */
@ConfigurationProperties(prefix = "infinity.knowledge.retrieval.space-router")
public record SpaceRouterProperties(
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

    /** 补齐安全默认值并拒绝无界模型调用。 */
    public SpaceRouterProperties {
        stageTimeout = stageTimeout == null ? Duration.ofSeconds(6) : stageTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        if (stageTimeout.isZero() || stageTimeout.isNegative()
                || stageTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "space router stageTimeout must be positive and at most 30 seconds"
            );
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(stageTimeout) > 0) {
            throw new IllegalArgumentException(
                    "space router requestTimeout must be positive and not exceed stageTimeout"
            );
        }
        endpoint = endpoint == null
                ? URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions")
                : endpoint;
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? "glm-5.2" : model.strip();
        maxAttempts = maxAttempts < 1 ? 1 : maxAttempts;
        if (maxAttempts > 2) {
            throw new IllegalArgumentException("space router maxAttempts must not exceed 2");
        }
        initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        maxInputCharacters = maxInputCharacters < 1 ? 32_000 : maxInputCharacters;
        maximumPromptTokens = maximumPromptTokens < 1 ? 8_192 : maximumPromptTokens;
        if (maximumPromptTokens > 500_000) {
            throw new IllegalArgumentException(
                    "space router maximumPromptTokens must not exceed 500000"
            );
        }
        maxOutputTokens = maxOutputTokens < 1 ? 512 : maxOutputTokens;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (!proxyHost.isEmpty() && (proxyPort < 1 || proxyPort > 65_535)) {
            throw new IllegalArgumentException(
                    "space router proxyPort must be valid when proxyHost is configured"
            );
        }
    }
}
