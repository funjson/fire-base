package dev.infinityknowledge.provider.zhipu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 使用本地 HTTP Server 验证智谱协议、重试和响应校验。
 */
class ZhipuEmbeddingProviderTest {
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
     * 验证 429 会执行有界退避，随后按输入顺序返回向量。
     *
     * @throws IOException 创建本地服务器失败
     */
    @Test
    void retriesRateLimitAndParsesVectors() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 429, "{\"error\":{\"message\":\"limited\"}}");
            } else {
                respond(exchange, 200, """
                        {
                          "data": [
                            {"index": 0, "embedding": [0.1, 0.2]},
                            {"index": 1, "embedding": [0.3, 0.4]}
                          ]
                        }
                        """);
            }
        });
        List<Duration> sleeps = new ArrayList<>();
        ZhipuEmbeddingProvider provider = provider(sleeps::add, "secret-key");

        var vectors = provider.embed(
                List.of("订单服务", "库存服务"),
                new EmbeddingSpec("zhipu", "embedding-3", 2)
        );

        assertEquals(2, requests.get());
        assertEquals(List.of(Duration.ofMillis(1)), sleeps);
        assertEquals(List.of(0.1D, 0.2D), vectors.getFirst().values());
        assertEquals(1, vectors.get(1).index());
    }

    /**
     * 验证异常不会包含 API Key 或 Provider 响应正文。
     *
     * @throws IOException 创建本地服务器失败
     */
    @Test
    void redactsProviderFailure() throws IOException {
        server = server(exchange -> respond(
                exchange,
                400,
                "{\"error\":{\"message\":\"sensitive provider body\"}}"
        ));
        ZhipuEmbeddingProvider provider = provider(duration -> {
        }, "secret-key");

        EmbeddingProviderException failure = assertThrows(
                EmbeddingProviderException.class,
                () -> provider.embed(
                        List.of("敏感文档正文"),
                        new EmbeddingSpec("zhipu", "embedding-3", 2)
                )
        );

        assertFalse(failure.getMessage().contains("secret-key"));
        assertFalse(failure.getMessage().contains("sensitive provider body"));
        assertFalse(failure.getMessage().contains("敏感文档正文"));
    }

    /**
     * 创建指向本地服务器的 Provider。
     *
     * @param sleeper 测试等待器
     * @param apiKey 测试密钥
     * @return Provider
     */
    private ZhipuEmbeddingProvider provider(RetrySleeper sleeper, String apiKey) {
        URI endpoint = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings"
        );
        return new ZhipuEmbeddingProvider(
                new ZhipuEmbeddingConfig(
                        endpoint,
                        apiKey,
                        Duration.ofSeconds(2),
                        64,
                        2,
                        Duration.ofMillis(1)
                ),
                HttpClient.newHttpClient(),
                JsonMapper.builder().build(),
                sleeper
        );
    }

    /**
     * 启动随机端口的本地 HTTP Server。
     *
     * @param handler 请求处理器
     * @return 已启动服务器
     * @throws IOException 创建失败
     */
    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/embeddings", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        value.start();
        return value;
    }

    /**
     * 返回 JSON 响应。
     *
     * @param exchange HTTP 交换
     * @param status HTTP 状态
     * @param body JSON 正文
     * @throws IOException 写响应失败
     */
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

