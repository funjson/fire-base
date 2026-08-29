package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.spi.model.ModelMessage;
import dev.infinityknowledge.spi.model.ModelTokenEstimate;
import dev.infinityknowledge.spi.model.ModelTokenEstimateRequest;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 使用智谱 {@code /paas/v4/tokenizer} 精确计算 GLM 聊天 Prompt Token 数。
 *
 * <p>官方协议只返回数量而不返回 Token offset，因此本实现绝不能注册为 Chunk
 * {@code TokenCounter}，也不能承担文本硬切。</p>
 */
public final class ZhipuModelTokenEstimator implements ModelTokenEstimator {
    public static final String PROVIDER_ID = "zhipu";
    public static final String VERSION = "paas-v4-tokenizer-v1";
    private static final Set<String> SUPPORTED_MODELS = Set.of("glm-5.1", "glm-5.2");

    private final ZhipuTokenizerConfig config;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final RetrySleeper sleeper;

    /** 创建具有有界网络、重试和输入预算的智谱计数适配器。 */
    public ZhipuModelTokenEstimator(
            ZhipuTokenizerConfig config,
            HttpClient httpClient,
            JsonMapper jsonMapper,
            RetrySleeper sleeper
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> supportedModelIds() {
        return SUPPORTED_MODELS;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Duration maximumLatency() {
        return config.maximumLatency();
    }

    /**
     * 对完整消息列表执行一次精确计数；同一次生成的重试不会重复消耗 Tokenizer 请求。
     */
    @Override
    public ModelTokenEstimate estimate(ModelTokenEstimateRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        validateRequest(request);
        Duration backoff = config.initialBackoff();
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        httpRequest(request),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parse(response.body());
                }
                if (!retryable(response.statusCode()) || attempt == config.maxAttempts()) {
                    throw new TokenizerProviderException(
                            "Zhipu tokenizer request failed with HTTP " + response.statusCode()
                    );
                }
            } catch (IOException networkFailure) {
                if (attempt == config.maxAttempts()) {
                    throw new TokenizerProviderException(
                            "Zhipu tokenizer network request failed",
                            networkFailure
                    );
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TokenizerProviderException(
                        "Zhipu tokenizer request was interrupted",
                        interrupted
                );
            }
            waitBeforeRetry(backoff);
            backoff = backoff.multipliedBy(2);
        }
        throw new TokenizerProviderException("Zhipu tokenizer retry budget exhausted");
    }

    private void validateRequest(ModelTokenEstimateRequest request) {
        if (!supports(request.providerId(), request.modelId())) {
            throw new IllegalArgumentException(
                    "Zhipu tokenizer supports only glm-5.1 and glm-5.2 generation models"
            );
        }
        if (request.messages().stream().noneMatch(message ->
                message.role() == ModelMessage.Role.USER)) {
            throw new IllegalArgumentException(
                    "Zhipu tokenizer messages must contain a user message"
            );
        }
        long characters = request.messages().stream()
                .mapToLong(message -> message.content().length())
                .sum();
        if (characters > config.maximumInputCharacters()) {
            throw new IllegalArgumentException(
                    "Zhipu tokenizer messages exceed the configured character limit"
            );
        }
    }

    private HttpRequest httpRequest(ModelTokenEstimateRequest request) {
        List<Map<String, String>> messages = request.messages().stream()
                .map(message -> Map.of(
                        "role", message.role().wireName(),
                        "content", message.content()
                ))
                .toList();
        final String body;
        try {
            body = jsonMapper.writeValueAsString(Map.of(
                    "model", request.modelId(),
                    "messages", messages
            ));
        } catch (JacksonException serializationFailure) {
            throw new TokenizerProviderException(
                    "Unable to serialize Zhipu tokenizer request"
            );
        }
        return HttpRequest.newBuilder(config.endpoint())
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private ModelTokenEstimate parse(String body) {
        final JsonNode promptTokens;
        try {
            promptTokens = jsonMapper.readTree(body).path("usage").path("prompt_tokens");
        } catch (JacksonException parseFailure) {
            throw new TokenizerProviderException(
                    "Unable to parse Zhipu tokenizer response"
            );
        }
        if (!promptTokens.isIntegralNumber()) {
            throw new TokenizerProviderException(
                    "Zhipu tokenizer response does not contain an integer prompt token count"
            );
        }
        BigInteger value = promptTokens.bigIntegerValue();
        if (value.signum() <= 0
                || value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new TokenizerProviderException(
                    "Zhipu tokenizer response contains an invalid prompt token count"
            );
        }
        return new ModelTokenEstimate(value.intValue(), true, VERSION);
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private void waitBeforeRetry(Duration backoff) {
        try {
            sleeper.sleep(backoff);
        } catch (RuntimeException retryWaitFailure) {
            throw new TokenizerProviderException(
                    "Zhipu tokenizer retry wait failed"
            );
        }
    }
}
