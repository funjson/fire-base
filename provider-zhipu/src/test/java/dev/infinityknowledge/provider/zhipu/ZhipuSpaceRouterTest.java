package dev.infinityknowledge.provider.zhipu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingCandidate;
import dev.infinityknowledge.spi.retrieval.SpaceRoutingRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证模型只能对服务端提供的 allowedSpaces 做完整排序。 */
class ZhipuSpaceRouterTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ranksEveryAllowedSpaceWithoutExposingAuthorizationContext() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = server(exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            respond(exchange, completion("""
                    {"orderedSpaceIds":["orders","platform"]}
                    """));
        });
        var router = router();

        var result = router.rank(request());

        assertEquals(
                List.of(new KnowledgeSpaceId("orders"), new KnowledgeSpaceId("platform")),
                result.orderedSpaceIds()
        );
        assertEquals("zhipu", result.provider());
        assertFalse(requestBody.get().contains("tenant"));
        assertFalse(requestBody.get().contains("principal"));
        assertFalse(requestBody.get().contains("secret-key"));
    }

    @Test
    void rejectsModelResultThatOmitsAnAllowedSpace() throws IOException {
        server = server(exchange -> respond(exchange, completion("""
                {"orderedSpaceIds":["orders"]}
                """)));

        assertThrows(GenerationProviderException.class, () -> router().rank(request()));
    }

    @Test
    void rejectsModelResultThatAddsAnUnauthorizedSpace() throws IOException {
        server = server(exchange -> respond(exchange, completion("""
                {"orderedSpaceIds":["orders","private"]}
                """)));

        assertThrows(GenerationProviderException.class, () -> router().rank(request()));
    }

    private ZhipuSpaceRouter router() {
        JsonMapper mapper = JsonMapper.builder().build();
        return new ZhipuSpaceRouter(
                new ZhipuJsonGenerationClient(
                        new ZhipuGenerationConfig(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + server.getAddress().getPort()
                                                + "/chat"
                                ),
                                "secret-key",
                                "glm-test",
                                Duration.ofSeconds(2),
                                1,
                                Duration.ZERO,
                                24_000,
                                256
                        ),
                        HttpClient.newHttpClient(),
                        mapper,
                        ignored -> {
                        }
                ),
                mapper,
                "glm-test"
        );
    }

    private SpaceRoutingRequest request() {
        return new SpaceRoutingRequest(
                "订单服务的上游依赖是什么？",
                List.of(
                        new SpaceRoutingCandidate(
                                new KnowledgeSpaceId("platform"),
                                "平台知识",
                                "共享基础设施"
                        ),
                        new SpaceRoutingCandidate(
                                new KnowledgeSpaceId("orders"),
                                "订单服务",
                                "订单服务运行手册"
                        )
                )
        );
    }

    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/chat", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        value.start();
        return value;
    }

    private static String completion(String content) {
        try {
            return JsonMapper.builder().build().writeValueAsString(Map.of(
                    "choices", List.of(Map.of(
                            "message", Map.of("content", content)
                    ))
            ));
        } catch (tools.jackson.core.JacksonException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
