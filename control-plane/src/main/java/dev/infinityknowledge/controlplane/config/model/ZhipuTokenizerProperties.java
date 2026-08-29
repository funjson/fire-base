package dev.infinityknowledge.controlplane.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 智谱 GLM Prompt Token 计数的部署级网络配置。
 *
 * <p>模型不在这里重复配置；每次计数直接使用生成调用的 modelId。Endpoint、凭据和代理
 * 也不会进入 Space 配置或检索观测事件。</p>
 *
 * @param enabled 是否启用远程精确 Prompt Token 预算
 * @param endpoint 智谱 Tokenizer 完整端点
 * @param apiKey 智谱 API Key
 * @param requestTimeout 单次计数请求超时
 * @param maxAttempts 包含首次调用的最大尝试次数
 * @param initialBackoff 首次重试等待时间
 * @param maximumInputCharacters 完整消息计数的字符防滥用上限
 * @param proxyHost 可选代理主机
 * @param proxyPort 可选代理端口
 */
@ConfigurationProperties(prefix = "infinity.knowledge.model-tokenizers.zhipu")
public record ZhipuTokenizerProperties(
        boolean enabled,
        URI endpoint,
        String apiKey,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        int maximumInputCharacters,
        String proxyHost,
        int proxyPort
) {
    /** 补齐有界默认值，并拒绝无效代理配置。 */
    public ZhipuTokenizerProperties {
        endpoint = endpoint == null
                ? URI.create("https://open.bigmodel.cn/api/paas/v4/tokenizer")
                : endpoint;
        apiKey = apiKey == null ? "" : apiKey.strip();
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(1) : requestTimeout;
        maxAttempts = maxAttempts < 1 ? 1 : maxAttempts;
        initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        maximumInputCharacters = maximumInputCharacters < 1
                ? 500_000
                : maximumInputCharacters;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (!proxyHost.isEmpty() && (proxyPort < 1 || proxyPort > 65_535)) {
            throw new IllegalArgumentException(
                    "Zhipu tokenizer proxyPort must be valid when proxyHost is configured"
            );
        }
    }
}
