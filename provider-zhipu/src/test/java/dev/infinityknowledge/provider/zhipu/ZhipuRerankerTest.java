package dev.infinityknowledge.provider.zhipu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.RerankResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本地 HTTP Server 验证智谱 Rerank 协议、排序、输入边界和安全异常。
 */
class ZhipuRerankerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;

    /**
     * 关闭每个测试创建的 HTTP Server。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 验证官方协议字段、分数排序以及原候选身份保持不变。
     *
     * @throws IOException 创建本地服务器失败
     */
    @Test
    void sendsBoundedProtocolAndOrdersByProviderScore() throws IOException {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = server(exchange -> {
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {
                      "results": [
                        {"index": 0, "relevance_score": 0.25},
                        {"index": 1, "relevance_score": 0.95}
                      ]
                    }
                    """);
        });
        ZhipuReranker reranker = reranker(duration -> {
        }, "secret-key", 2, 4_096, 4_096, 20_000, 1);
        RetrievalCandidate first = candidate("第一条", "正文一", 1);
        RetrievalCandidate second = candidate("第二条", "正文二", 2);

        RerankResult result = reranker.rerank(
                "订单服务依赖什么？",
                List.of(first, second),
                2
        );

        assertEquals(List.of(second, first), result.orderedCandidates());
        assertEquals(2, result.scoredCandidateCount());
        assertEquals(0.95D, result.scoresByChunk().get(second.chunkId()));
        assertEquals(0.25D, result.scoresByChunk().get(first.chunkId()));
        assertEquals("ZHIPU_RERANK", result.reasonCode());
        assertEquals("Bearer secret-key", authorization.get());
        assertEquals("rerank", requestBody.get().path("model").asString());
        assertEquals(2, requestBody.get().path("top_n").asInt());
        assertFalse(requestBody.get().path("return_documents").asBoolean(true));
        assertFalse(requestBody.get().path("return_raw_scores").asBoolean(true));
        assertEquals(2, requestBody.get().path("documents").size());
    }

    /**
     * 验证 Unicode 字符截断不会留下孤立代理项，并且超出模型候选预算的结果按原顺序补齐。
     *
     * @throws IOException 创建本地服务器失败
     */
    @Test
    void truncatesUnicodeSafelyAndFillsUnscoredCandidatesStably() throws IOException {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = server(exchange -> {
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"results":[{"index":0,"relevance_score":0.8}]}
                    """);
        });
        ZhipuReranker reranker = reranker(duration -> {
        }, "secret-key", 1, 3, 10, 100, 1);
        RetrievalCandidate first = candidate("😀条目", "😀😀😀😀正文", 1);
        RetrievalCandidate second = candidate("未评分", "补齐正文", 2);

        RerankResult result = reranker.rerank(
                "😀😀😀😀",
                List.of(first, second),
                2
        );

        String boundedQuery = requestBody.get().path("query").asString();
        String boundedDocument = requestBody.get().path("documents").get(0).asString();
        assertEquals(3, boundedQuery.codePointCount(0, boundedQuery.length()));
        assertTrue(boundedDocument.codePointCount(0, boundedDocument.length()) <= 10);
        assertFalse(Character.isHighSurrogate(
                boundedDocument.charAt(boundedDocument.length() - 1)
        ));
        assertEquals(List.of(first, second), result.orderedCandidates());
        assertEquals(1, result.scoredCandidateCount());
        assertEquals(0.8D, result.scoresByChunk().get(first.chunkId()));
        assertFalse(result.scoresByChunk().containsKey(second.chunkId()));
        assertEquals("ZHIPU_RERANK_PARTIAL", result.reasonCode());
    }

    /**
     * 验证 429 只执行有界重试且遵循配置的退避时间。
     *
     * @throws IOException 创建本地服务器失败
     */
    @Test
    void retriesRateLimitWithinConfiguredBudget() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 429, "{\"error\":{\"message\":\"limited\"}}");
            } else {
                respond(exchange, 200, """
                        {"results":[{"index":0,"relevance_score":0.7}]}
                        """);
            }
        });
        List<Duration> sleeps = new ArrayList<>();
        ZhipuReranker reranker = reranker(
                sleeps::add,
                "secret-key",
                2,
                4_096,
                4_096,
                20_000,
                2
        );

        RerankResult result = reranker.rerank(
                "查询",
                List.of(candidate("条目", "正文", 1)),
                1
        );

        assertEquals(1, result.orderedCandidates().size());
        assertEquals(1, result.scoredCandidateCount());
        assertEquals(2, requests.get());
        assertEquals(List.of(Duration.ofMillis(1)), sleeps);
    }

    /**
     * 验证畸形索引直接失败，且异常不泄露密钥、正文或上游响应正文。
     *
     * @throws IOException 创建本地服务器失败
     */
    @Test
    void rejectsInvalidResponseWithoutLeakingSensitiveText() throws IOException {
        server = server(exchange -> respond(exchange, 200, """
                {
                  "results": [
                    {"index": 0, "relevance_score": 0.9},
                    {"index": 0, "relevance_score": 0.8}
                  ],
                  "secret": "sensitive provider body"
                }
                """));
        ZhipuReranker reranker = reranker(
                duration -> {
                },
                "secret-key",
                2,
                4_096,
                4_096,
                20_000,
                1
        );

        RerankProviderException failure = assertThrows(
                RerankProviderException.class,
                () -> reranker.rerank(
                        "敏感查询",
                        List.of(
                                candidate("一", "敏感正文一", 1),
                                candidate("二", "敏感正文二", 2)
                        ),
                        2
                )
        );

        assertFalse(failure.getMessage().contains("secret-key"));
        assertFalse(failure.getMessage().contains("敏感查询"));
        assertFalse(failure.getMessage().contains("敏感正文"));
        assertFalse(failure.getMessage().contains("sensitive provider body"));
    }

    private ZhipuReranker reranker(
            RetrySleeper sleeper,
            String apiKey,
            int maxCandidates,
            int maxQueryCharacters,
            int maxDocumentCharacters,
            int maxTotalCharacters,
            int maxAttempts
    ) {
        URI endpoint = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/rerank"
        );
        return new ZhipuReranker(
                new ZhipuRerankConfig(
                        endpoint,
                        apiKey,
                        "rerank",
                        Duration.ofSeconds(2),
                        maxCandidates,
                        maxQueryCharacters,
                        maxDocumentCharacters,
                        maxTotalCharacters,
                        maxAttempts,
                        Duration.ofMillis(1)
                ),
                HttpClient.newHttpClient(),
                JSON,
                sleeper
        );
    }

    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/rerank", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        value.start();
        return value;
    }

    private static RetrievalCandidate candidate(String title, String content, int rank) {
        return new RetrievalCandidate(
                UUID.randomUUID(),
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                DocumentId.random(),
                UUID.randomUUID(),
                RetrievalChannel.KEYWORD,
                rank,
                0.5D,
                title,
                List.of("章节"),
                content,
                "https://knowledge.example/" + rank,
                Map.of()
        );
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /**
     * 允许测试处理器抛出 IOException。
     */
    @FunctionalInterface
    private interface ThrowingHandler {

        /**
         * 处理一次 HTTP 交换。
         *
         * @param exchange HTTP 交换
         * @throws IOException 读写失败
         */
        void handle(HttpExchange exchange) throws IOException;
    }
}
