package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 绑定智谱 Rerank API 的连接和有界重试参数。
 *
 * @param endpoint Rerank 完整端点
 * @param apiKey API Key
 * @param model 模型编码
 * @param requestTimeout 单次 HTTP 请求超时
 * @param maxAttempts 包含首次调用的最大尝试次数
 * @param initialBackoff 首次重试退避时间
 * @param proxyHost 可选代理主机
 * @param proxyPort 可选代理端口
 */
@ConfigurationProperties(prefix = "infinity.knowledge.retrieval.reranker.zhipu")
public record ZhipuRerankerProperties(
        URI endpoint,
        String apiKey,
        String model,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        String proxyHost,
        int proxyPort
) {

    /**
     * 应用安全默认值；凭据仍必须由外部环境注入。
     */
    public ZhipuRerankerProperties {
        endpoint = endpoint == null
                ? URI.create("https://open.bigmodel.cn/api/paas/v4/rerank")
                : endpoint;
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? "rerank" : model.strip();
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (maxAttempts < 1) {
            maxAttempts = 1;
        }
        if (!proxyHost.isEmpty() && (proxyPort < 1 || proxyPort > 65_535)) {
            throw new IllegalArgumentException(
                    "reranker proxyPort must be valid when proxyHost is configured"
            );
        }
    }
}
