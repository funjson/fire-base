package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 保存智谱 Rerank API 的连接、输入预算和有界重试配置。
 *
 * @param endpoint Rerank 完整端点
 * @param apiKey API Key
 * @param model 模型编码
 * @param requestTimeout 单次 HTTP 请求超时
 * @param maxCandidates 单次最多参与模型评分的候选数
 * @param maxQueryCharacters 查询最大 Unicode 字符数
 * @param maxDocumentCharacters 单个候选最大 Unicode 字符数
 * @param maxTotalCharacters 单次请求全部查询和候选的最大 Unicode 字符数
 * @param maxAttempts 包含首次调用的最大尝试次数
 * @param initialBackoff 首次重试退避时间
 */
public record ZhipuRerankConfig(
        URI endpoint,
        String apiKey,
        String model,
        Duration requestTimeout,
        int maxCandidates,
        int maxQueryCharacters,
        int maxDocumentCharacters,
        int maxTotalCharacters,
        int maxAttempts,
        Duration initialBackoff
) {
    private static final int PROVIDER_MAX_CANDIDATES = 128;
    private static final int PROVIDER_MAX_CHARACTERS = 4_096;

    /**
     * 校验官方协议上限和本地资源边界，避免配置把在线请求放大为无界负载。
     */
    public ZhipuRerankConfig {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint must use HTTP or HTTPS");
        }
        apiKey = DomainChecks.requiredText(apiKey, "apiKey", 4_096);
        model = DomainChecks.requiredText(model, "model", 128);
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (requestTimeout.isZero()
                || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive and at most one minute"
            );
        }
        if (maxCandidates < 1 || maxCandidates > PROVIDER_MAX_CANDIDATES) {
            throw new IllegalArgumentException("maxCandidates must be between 1 and 128");
        }
        validateCharacterLimit(maxQueryCharacters, "maxQueryCharacters");
        validateCharacterLimit(maxDocumentCharacters, "maxDocumentCharacters");
        int providerMaximumTotal = PROVIDER_MAX_CHARACTERS
                * (PROVIDER_MAX_CANDIDATES + 1);
        if (maxTotalCharacters <= maxQueryCharacters
                || maxTotalCharacters > providerMaximumTotal) {
            throw new IllegalArgumentException(
                    "maxTotalCharacters must exceed maxQueryCharacters and stay within "
                            + "the provider request maximum"
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
    }

    private static void validateCharacterLimit(int value, String name) {
        if (value < 1 || value > PROVIDER_MAX_CHARACTERS) {
            throw new IllegalArgumentException(name + " must be between 1 and 4096");
        }
    }
}
