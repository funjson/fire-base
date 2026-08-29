package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.spi.model.ModelMessage;
import dev.infinityknowledge.spi.model.ModelTokenEstimate;
import dev.infinityknowledge.spi.model.ModelTokenEstimateRequest;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 为知识图谱、Wiki 编译和查询规划提供最小 GLM JSON 模式客户端。
 *
 * <p>客户端只暴露系统约束和受预算治理的输入，厂商响应类型不会穿透 Adapter 边界。</p>
 */
public final class ZhipuJsonGenerationClient {
    private final ZhipuGenerationConfig config;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final RetrySleeper sleeper;
    private final ModelTokenEstimator tokenEstimator;

    public ZhipuJsonGenerationClient(
            ZhipuGenerationConfig config,
            HttpClient httpClient,
            JsonMapper jsonMapper,
            RetrySleeper sleeper
    ) {
        this(config, httpClient, jsonMapper, sleeper, null);
    }

    /**
     * 创建启用同模型精确 Prompt Token 预算的结构化生成客户端。
     *
     * <p>Estimator 不保存独立 tokenizerModel；构造时即验证它支持生成配置中的模型。</p>
     */
    public ZhipuJsonGenerationClient(
            ZhipuGenerationConfig config,
            HttpClient httpClient,
            JsonMapper jsonMapper,
            RetrySleeper sleeper,
            ModelTokenEstimator tokenEstimator
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
        this.tokenEstimator = tokenEstimator;
        if (tokenEstimator != null
                && !tokenEstimator.supports(ZhipuModelTokenEstimator.PROVIDER_ID, config.model())) {
            throw new IllegalArgumentException(
                    "prompt token estimator does not support the configured generation model"
            );
        }
    }

    /**
     * 发送一次有界 JSON 模式请求并解析助手返回内容。
     */
    public JsonNode generate(String instruction, String input) {
        instruction = requireText(instruction, "instruction", 32_000);
        input = requireText(input, "input", config.maxInputCharacters());
        List<ModelMessage> messages = List.of(
                new ModelMessage(ModelMessage.Role.SYSTEM, instruction),
                new ModelMessage(ModelMessage.Role.USER, input)
        );
        validatePromptBudget(messages);
        Duration backoff = config.initialBackoff();
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request(messages),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parse(response.body());
                }
                if (!retryable(response.statusCode()) || attempt == config.maxAttempts()) {
                    throw new GenerationProviderException(
                            "Zhipu generation request failed with HTTP " + response.statusCode()
                    );
                }
            } catch (IOException networkFailure) {
                if (attempt == config.maxAttempts()) {
                    throw new GenerationProviderException(
                            "Zhipu generation network request failed",
                            networkFailure
                    );
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GenerationProviderException(
                        "Zhipu generation request was interrupted",
                        interrupted
                );
            }
            sleeper.sleep(backoff);
            backoff = backoff.multipliedBy(2);
        }
        throw new GenerationProviderException("Zhipu generation retry budget exhausted");
    }

    /**
     * 返回输入字符预算，供上层在调用前构建有界批次。
     */
    public int maximumInputCharacters() {
        return config.maxInputCharacters();
    }

    private void validatePromptBudget(List<ModelMessage> messages) {
        if (tokenEstimator == null) {
            return;
        }
        final ModelTokenEstimate estimate;
        try {
            estimate = tokenEstimator.estimate(new ModelTokenEstimateRequest(
                    ZhipuModelTokenEstimator.PROVIDER_ID,
                    config.model(),
                    messages
            ));
        } catch (RuntimeException estimationFailure) {
            /*
             * 第三方 Estimator 的异常链可能携带 Prompt 或响应片段，因此这里只转换为
             * 稳定失败类别，不把原异常挂到业务异常上。
             */
            throw new GenerationProviderException(
                    "Zhipu generation prompt token estimation failed"
            );
        }
        if (estimate == null
                || !tokenEstimator.version().equals(estimate.estimatorVersion())) {
            throw new GenerationProviderException(
                    "Zhipu generation prompt token estimator returned an invalid contract"
            );
        }
        if (!estimate.exact()) {
            throw new GenerationProviderException(
                    "Zhipu generation requires an exact prompt token estimate"
            );
        }
        if (estimate.promptTokens() > config.maximumPromptTokens()) {
            throw new GenerationProviderException(
                    "Zhipu generation prompt exceeds the configured token budget"
            );
        }
    }

    private HttpRequest request(List<ModelMessage> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("messages", messages.stream().map(message -> Map.of(
                "role", message.role().wireName(),
                "content", message.content()
        )).toList());
        payload.put("response_format", Map.of("type", "json_object"));
        if (tokenEstimator != null) {
            /* 检索短任务关闭 GLM-5.x 默认思考，避免预算外延迟；旧 Graph/Wiki 调用保持原协议。 */
            payload.put("thinking", Map.of("type", "disabled"));
        }
        payload.put("temperature", 0.1D);
        payload.put("max_tokens", config.maxOutputTokens());
        payload.put("stream", false);
        final String body;
        try {
            body = jsonMapper.writeValueAsString(payload);
        } catch (JacksonException serializationFailure) {
            throw new GenerationProviderException(
                    "Unable to serialize Zhipu generation request",
                    serializationFailure
            );
        }
        return HttpRequest.newBuilder(config.endpoint())
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private JsonNode parse(String body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new GenerationProviderException(
                        "Zhipu generation response does not contain a choice"
                );
            }
            String content = choices.get(0).path("message").path("content").asString("");
            if (content.isBlank()) {
                throw new GenerationProviderException(
                        "Zhipu generation response content is blank"
                );
            }
            return jsonMapper.readTree(content);
        } catch (JacksonException parseFailure) {
            throw new GenerationProviderException(
                    "Unable to parse Zhipu generation response",
                    parseFailure
            );
        }
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain between 1 and " + maximumLength + " characters"
            );
        }
        return normalized;
    }
}
