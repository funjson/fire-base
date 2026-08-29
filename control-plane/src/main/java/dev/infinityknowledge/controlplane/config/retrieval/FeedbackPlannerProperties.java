package dev.infinityknowledge.controlplane.config.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 定义固定 Chain 反馈查询生成模型的超时、预算和网络边界。
 *
 * @param enabled 是否启用模型反馈规划
 * @param stageTimeout 反馈规划阶段硬超时
 * @param endpoint 智谱 Chat Completions 端点
 * @param apiKey 智谱 API Key
 * @param model 反馈规划模型
 * @param requestTimeout 单次 HTTP 请求超时
 * @param maxAttempts 包含首次调用的最大尝试次数
 * @param initialBackoff 首次重试等待时间
 * @param maxInputCharacters 单次模型输入字符预算
 * @param maxOutputTokens 单次模型输出 Token 预算
 * @param proxyHost 可选网络代理主机
 * @param proxyPort 可选网络代理端口
 */
@ConfigurationProperties(prefix = "infinity.knowledge.retrieval.feedback-planner")
public record FeedbackPlannerProperties(
        boolean enabled,
        Duration stageTimeout,
        URI endpoint,
        String apiKey,
        String model,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        int maxInputCharacters,
        int maxOutputTokens,
        String proxyHost,
        int proxyPort
) {

    /** 补齐安全默认值并校验反馈规划不会形成无界模型调用。 */
    public FeedbackPlannerProperties {
        stageTimeout = stageTimeout == null ? Duration.ofSeconds(4) : stageTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        if (stageTimeout.isZero() || stageTimeout.isNegative()
                || stageTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "feedback planner stageTimeout must be positive and at most 30 seconds"
            );
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(stageTimeout) > 0) {
            throw new IllegalArgumentException(
                    "feedback planner requestTimeout must be positive and not exceed stageTimeout"
            );
        }
        endpoint = endpoint == null
                ? URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions")
                : endpoint;
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? "glm-4.5-flash" : model.strip();
        maxAttempts = maxAttempts < 1 ? 1 : maxAttempts;
        if (maxAttempts > 2) {
            throw new IllegalArgumentException("feedback planner maxAttempts must not exceed 2");
        }
        initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        maxInputCharacters = maxInputCharacters < 1 ? 24_000 : maxInputCharacters;
        maxOutputTokens = maxOutputTokens < 1 ? 512 : maxOutputTokens;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (!proxyHost.isEmpty() && (proxyPort < 1 || proxyPort > 65_535)) {
            throw new IllegalArgumentException(
                    "feedback planner proxyPort must be valid when proxyHost is configured"
            );
        }
    }
}
