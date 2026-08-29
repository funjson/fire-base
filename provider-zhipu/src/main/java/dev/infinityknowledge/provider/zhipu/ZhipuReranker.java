package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.spi.retrieval.RerankResult;
import dev.infinityknowledge.spi.retrieval.Reranker;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 通过智谱 `/api/paas/v4/rerank` 协议对已授权候选执行专用模型精排。
 *
 * <p>适配器只按上游返回的原输入索引重排候选，不创建新候选，也不记录查询、候选正文、
 * API Key 或上游响应正文。任何协议异常都会交给 Knowledge Gateway 回退到 RRF 顺序。</p>
 */
public final class ZhipuReranker implements Reranker {
    private final ZhipuRerankConfig config;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final RetrySleeper sleeper;

    /**
     * 创建有界智谱文本重排适配器。
     *
     * @param config 连接与输入预算
     * @param httpClient HTTP 客户端
     * @param jsonMapper JSON 编解码器
     * @param sleeper 有界重试等待器
     */
    public ZhipuReranker(
            ZhipuRerankConfig config,
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
     * 对候选前缀执行模型评分，并在模型预算不足以覆盖返回数量时按原顺序稳定补齐。
     *
     * @param query 查询文本
     * @param candidates 已授权且处于活动修订的候选
     * @param limit 返回上限
     * @return 模型相关性降序候选、本次评分和稳定原因码
     */
    @Override
    public RerankResult rerank(
            String query,
            List<RetrievalCandidate> candidates,
            int limit
    ) {
        Objects.requireNonNull(query, "query must not be null");
        candidates = List.copyOf(Objects.requireNonNull(
                candidates,
                "candidates must not be null"
        ));
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (candidates.isEmpty()) {
            return new RerankResult(List.of(), Map.of(), 0, "ZHIPU_RERANK");
        }

        BoundedInput input = boundedInput(query, candidates);
        int scoredLimit = Math.min(limit, input.documents().size());
        List<ScoredIndex> scores = invokeWithRetry(
                input.query(),
                input.documents(),
                scoredLimit
        );
        List<RetrievalCandidate> ordered = new ArrayList<>(Math.min(
                limit,
                candidates.size()
        ));
        Map<UUID, Double> scoresByChunk = new LinkedHashMap<>();
        for (ScoredIndex score : scores) {
            RetrievalCandidate candidate = candidates.get(score.index());
            ordered.add(candidate);
            scoresByChunk.put(candidate.chunkId(), score.score());
        }
        int resultLimit = Math.min(limit, candidates.size());
        for (RetrievalCandidate candidate : candidates) {
            if (ordered.size() >= resultLimit) {
                break;
            }
            if (!ordered.contains(candidate)) {
                ordered.add(candidate);
            }
        }
        boolean partial = scoresByChunk.size() < ordered.size();
        return new RerankResult(
                ordered,
                scoresByChunk,
                scoresByChunk.size(),
                partial ? "ZHIPU_RERANK_PARTIAL" : "ZHIPU_RERANK"
        );
    }

    private BoundedInput boundedInput(
            String query,
            List<RetrievalCandidate> candidates
    ) {
        String boundedQuery = truncateCodePoints(query.strip(), config.maxQueryCharacters());
        int usedCharacters = characterCount(boundedQuery);
        List<String> documents = new ArrayList<>(Math.min(
                candidates.size(),
                config.maxCandidates()
        ));
        int candidateLimit = Math.min(candidates.size(), config.maxCandidates());
        for (int index = 0; index < candidateLimit; index++) {
            int remaining = config.maxTotalCharacters() - usedCharacters;
            if (remaining < 1) {
                break;
            }
            String text = candidateText(candidates.get(index));
            String bounded = truncateCodePoints(
                    text,
                    Math.min(config.maxDocumentCharacters(), remaining)
            );
            if (!bounded.isBlank()) {
                documents.add(bounded);
                usedCharacters += characterCount(bounded);
            }
        }
        if (documents.isEmpty()) {
            throw new IllegalStateException("reranker character budget excludes all candidates");
        }
        return new BoundedInput(boundedQuery, documents);
    }

    private List<ScoredIndex> invokeWithRetry(
            String query,
            List<String> documents,
            int topN
    ) {
        Duration backoff = config.initialBackoff();
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request(query, documents, topN),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parse(response.body(), documents.size(), topN);
                }
                if (!retryable(response.statusCode()) || attempt == config.maxAttempts()) {
                    throw new RerankProviderException(
                            "Zhipu rerank request failed with HTTP " + response.statusCode()
                    );
                }
            } catch (IOException networkFailure) {
                if (attempt == config.maxAttempts()) {
                    throw new RerankProviderException(
                            "Zhipu rerank network request failed",
                            networkFailure
                    );
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RerankProviderException(
                        "Zhipu rerank request was interrupted",
                        interrupted
                );
            }
            sleeper.sleep(backoff);
            backoff = backoff.multipliedBy(2);
        }
        throw new RerankProviderException("Zhipu rerank retry budget exhausted");
    }

    private HttpRequest request(String query, List<String> documents, int topN) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("query", query);
        payload.put("documents", documents);
        payload.put("top_n", topN);
        payload.put("return_documents", false);
        payload.put("return_raw_scores", false);
        final String body;
        try {
            body = jsonMapper.writeValueAsString(payload);
        } catch (JacksonException serializationFailure) {
            throw new RerankProviderException(
                    "Unable to serialize Zhipu rerank request",
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

    private List<ScoredIndex> parse(String body, int candidateCount, int expectedCount) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.size() != expectedCount) {
                throw new RerankProviderException(
                        "Zhipu rerank response count differs from request"
                );
            }
            List<ScoredIndex> scores = new ArrayList<>(results.size());
            Set<Integer> indexes = new HashSet<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                JsonNode relevance = result.path("relevance_score");
                double score = relevance.asDouble(Double.NaN);
                if (index < 0
                        || index >= candidateCount
                        || !indexes.add(index)
                        || !relevance.isNumber()
                        || !Double.isFinite(score)
                        || score < 0.0D
                        || score > 1.0D) {
                    throw new RerankProviderException(
                            "Zhipu rerank response contains an invalid index or score"
                    );
                }
                scores.add(new ScoredIndex(index, score));
            }
            scores.sort(Comparator.comparingDouble(ScoredIndex::score)
                    .reversed()
                    .thenComparingInt(ScoredIndex::index));
            return List.copyOf(scores);
        } catch (JacksonException parseFailure) {
            throw new RerankProviderException(
                    "Unable to parse Zhipu rerank response",
                    parseFailure
            );
        }
    }

    private String candidateText(RetrievalCandidate candidate) {
        Objects.requireNonNull(candidate, "candidates must not contain null values");
        String section = String.join(" / ", candidate.sectionPath());
        return candidate.title() + "\n" + section + "\n" + candidate.content();
    }

    private String truncateCodePoints(String value, int maximumCharacters) {
        int codePoints = characterCount(value);
        if (codePoints <= maximumCharacters) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCharacters));
    }

    private int characterCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private record BoundedInput(String query, List<String> documents) {
    }

    private record ScoredIndex(int index, double score) {
    }
}
