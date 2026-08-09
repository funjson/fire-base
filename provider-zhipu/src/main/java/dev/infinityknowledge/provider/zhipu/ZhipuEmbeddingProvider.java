package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通过智谱 `/api/paas/v4/embeddings` 协议批量生成文本向量。
 *
 * <p>实现对 429、408 和 5xx 响应进行有界退避重试。异常和 Trace 只包含状态码，
 * 不包含请求文本、API Key 或响应正文。</p>
 */
public final class ZhipuEmbeddingProvider implements EmbeddingProvider {
    private final ZhipuEmbeddingConfig config;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final RetrySleeper sleeper;

    /**
     * 创建智谱适配器。
     *
     * @param config Provider 配置
     * @param httpClient HTTP 客户端
     * @param jsonMapper Jackson 3 Mapper
     * @param sleeper 重试等待器
     */
    public ZhipuEmbeddingProvider(
            ZhipuEmbeddingConfig config,
            HttpClient httpClient,
            JsonMapper jsonMapper,
            RetrySleeper sleeper
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    /**
     * 按智谱最大批量限制切分请求，并恢复全局输入序号。
     *
     * @param texts 非空文本列表
     * @param spec 固定模型和维度
     * @return 与输入顺序一致的向量
     */
    @Override
    public List<EmbeddingVector> embed(List<String> texts, EmbeddingSpec spec) {
        Objects.requireNonNull(texts, "texts must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        if (!"zhipu".equals(spec.providerId())) {
            throw new IllegalArgumentException("unsupported provider " + spec.providerId());
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        if (texts.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("embedding texts must not contain blank values");
        }
        List<EmbeddingVector> result = new ArrayList<>(texts.size());
        for (int offset = 0; offset < texts.size(); offset += config.maxBatchSize()) {
            int end = Math.min(texts.size(), offset + config.maxBatchSize());
            List<EmbeddingVector> batch = invokeWithRetry(texts.subList(offset, end), spec);
            for (EmbeddingVector vector : batch) {
                result.add(new EmbeddingVector(offset + vector.index(), vector.values()));
            }
        }
        result.sort(Comparator.comparingInt(EmbeddingVector::index));
        validateComplete(result, texts.size(), spec.dimensions());
        return List.copyOf(result);
    }

    /**
     * 对可重试网络和 HTTP 错误执行指数退避。
     *
     * @param texts 当前批次文本
     * @param spec 模型规格
     * @return 当前批次向量
     */
    private List<EmbeddingVector> invokeWithRetry(
            List<String> texts,
            EmbeddingSpec spec
    ) {
        Duration backoff = config.initialBackoff();
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request(texts, spec),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parse(response.body(), texts.size(), spec.dimensions());
                }
                if (!retryableStatus(response.statusCode()) || attempt == config.maxAttempts()) {
                    throw new EmbeddingProviderException(
                            "Zhipu embedding request failed with HTTP " + response.statusCode()
                    );
                }
            } catch (IOException networkFailure) {
                if (attempt == config.maxAttempts()) {
                    throw new EmbeddingProviderException(
                            "Zhipu embedding network request failed",
                            networkFailure
                    );
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new EmbeddingProviderException(
                        "Zhipu embedding request was interrupted",
                        interrupted
                );
            }
            sleeper.sleep(backoff);
            backoff = backoff.multipliedBy(2);
        }
        throw new EmbeddingProviderException("Zhipu embedding retry budget exhausted");
    }

    /**
     * 构造不记录正文的 HTTP 请求。
     *
     * @param texts 当前批次文本
     * @param spec 模型规格
     * @return HTTP 请求
     */
    private HttpRequest request(List<String> texts, EmbeddingSpec spec) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", spec.modelId());
        payload.put("input", texts);
        payload.put("dimensions", spec.dimensions());
        final String body;
        try {
            body = jsonMapper.writeValueAsString(payload);
        } catch (JacksonException serializationFailure) {
            throw new EmbeddingProviderException(
                    "Unable to serialize Zhipu embedding request",
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

    /**
     * 解析响应并验证索引、数量和向量维度。
     *
     * @param body 响应 JSON
     * @param expectedCount 预期向量数
     * @param expectedDimensions 预期维度
     * @return 当前批次向量
     */
    private List<EmbeddingVector> parse(
            String body,
            int expectedCount,
            int expectedDimensions
    ) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != expectedCount) {
                throw new EmbeddingProviderException(
                        "Zhipu embedding response count differs from request"
                );
            }
            List<EmbeddingVector> vectors = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode valuesNode = item.path("embedding");
                if (index < 0 || !valuesNode.isArray()
                        || valuesNode.size() != expectedDimensions) {
                    throw new EmbeddingProviderException(
                            "Zhipu embedding response has invalid index or dimensions"
                    );
                }
                List<Double> values = new ArrayList<>(expectedDimensions);
                for (JsonNode value : valuesNode) {
                    values.add(value.asDouble());
                }
                vectors.add(new EmbeddingVector(index, values));
            }
            vectors.sort(Comparator.comparingInt(EmbeddingVector::index));
            validateComplete(vectors, expectedCount, expectedDimensions);
            return List.copyOf(vectors);
        } catch (JacksonException parseFailure) {
            throw new EmbeddingProviderException(
                    "Unable to parse Zhipu embedding response",
                    parseFailure
            );
        }
    }

    /**
     * 判断状态码是否适合安全重试。
     *
     * @param statusCode HTTP 状态码
     * @return 是否重试
     */
    private boolean retryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    /**
     * 验证向量索引连续、数量正确且维度一致。
     *
     * @param vectors 向量列表
     * @param expectedCount 预期数量
     * @param expectedDimensions 预期维度
     */
    private void validateComplete(
            List<EmbeddingVector> vectors,
            int expectedCount,
            int expectedDimensions
    ) {
        if (vectors.size() != expectedCount) {
            throw new EmbeddingProviderException("Embedding result count differs from input");
        }
        for (int index = 0; index < vectors.size(); index++) {
            EmbeddingVector vector = vectors.get(index);
            if (vector.index() != index || vector.values().size() != expectedDimensions) {
                throw new EmbeddingProviderException(
                        "Embedding result order or dimensions are invalid"
                );
            }
        }
    }
}

