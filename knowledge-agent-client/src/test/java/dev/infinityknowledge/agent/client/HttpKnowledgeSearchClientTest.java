package dev.infinityknowledge.agent.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpKnowledgeSearchClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsBearerIdentityAndPreservesCorrelation() throws IOException {
        UUID requestId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("X-Request-Id", requestId.toString());
            respond(exchange, 200, """
                    {"requestId":"%s","traceId":"%s","tenantId":"demo",
                     "evidences":[{"id":"%s","content":"Redis 连接池已耗尽",
                     "relevance":0.91,"authority":90,"channels":["KEYWORD","VECTOR"],
                     "citation":{"documentId":"2ecb0d44-8bed-45de-98d1-744104c26315",
                     "revisionId":"%s","chunkId":"%s","title":"排障手册",
                     "sectionPath":["Redis"],"sourceUri":"obsidian://ops/runbook"}}],
                     "sufficient":true,"warnings":[],"generatedAt":"2026-08-11T08:00:00Z"}
                    """.formatted(requestId, traceId, evidenceId, revisionId, chunkId));
        });

        KnowledgeSearchResponse response = client("short-lived-token").search(
                requestId,
                new KnowledgeSearchRequest(
                        "Redis 连接池超时",
                        Set.of("engineering"),
                        5,
                        Map.of("language", "zh-CN")
                )
        );

        assertEquals("Bearer short-lived-token", authorization.get());
        assertTrue(body.get().contains("Redis 连接池超时"));
        assertFalse(body.get().contains("tenantId"));
        assertEquals(traceId, response.traceId());
        assertEquals(chunkId, response.evidences().getFirst().citation().chunkId());
    }

    @Test
    void exposesStableRetryableFailureWithoutReturningRawBody() throws IOException {
        UUID requestId = UUID.randomUUID();
        server = server(exchange -> {
            exchange.getResponseHeaders().set("X-Request-Id", requestId.toString());
            respond(exchange, 503, """
                    {"code":"WORK_QUEUE_SATURATED","message":"Try again later",
                     "requestId":"%s","timestamp":"2026-08-11T08:00:00Z",
                     "internal":"must-not-leak"}
                    """.formatted(requestId));
        });

        KnowledgeClientException failure = assertThrows(
                KnowledgeClientException.class,
                () -> client("token").search(requestId, request())
        );

        assertEquals("WORK_QUEUE_SATURATED", failure.code());
        assertEquals(requestId.toString(), failure.requestId());
        assertTrue(failure.retryable());
        assertFalse(failure.getMessage().contains("must-not-leak"));
    }

    @Test
    void rejectsMismatchedResponseCorrelation() throws IOException {
        UUID requestId = UUID.randomUUID();
        server = server(exchange -> {
            exchange.getResponseHeaders().set("X-Request-Id", UUID.randomUUID().toString());
            respond(exchange, 200, """
                    {"requestId":"%s","traceId":"%s","tenantId":"demo",
                     "evidences":[],"sufficient":false,"warnings":[],
                     "generatedAt":"2026-08-11T08:00:00Z"}
                    """.formatted(requestId, UUID.randomUUID()));
        });

        KnowledgeClientException failure = assertThrows(
                KnowledgeClientException.class,
                () -> client("token").search(requestId, request())
        );

        assertEquals("KNOWLEDGE_PROTOCOL_ERROR", failure.code());
        assertFalse(failure.retryable());
    }

    @Test
    void thinToolUsesSafeDefaultsAndReturnsEvidenceJson() throws Exception {
        UUID requestId = UUID.randomUUID();
        AtomicReference<String> body = new AtomicReference<>();
        server = server(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("X-Request-Id", requestId.toString());
            respond(exchange, 200, """
                    {"requestId":"%s","traceId":"%s","tenantId":"demo",
                     "evidences":[],"sufficient":false,"warnings":[],
                     "generatedAt":"2026-08-11T08:00:00Z"}
                    """.formatted(requestId, UUID.randomUUID()));
        });
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                client("token"),
                JsonMapper.builder().build()
        );

        String result = tool.execute(requestId, "{\"query\":\"where is the runbook\"}");

        assertTrue(body.get().contains("\"topK\":8"));
        assertTrue(result.contains(requestId.toString()));
        assertEquals("search_enterprise_knowledge", KnowledgeSearchTool.NAME);
    }

    private KnowledgeSearchRequest request() {
        return new KnowledgeSearchRequest("query", Set.of(), 8, Map.of());
    }

    private HttpKnowledgeSearchClient client(String token) {
        return new HttpKnowledgeSearchClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                JsonMapper.builder().build(),
                () -> token,
                Duration.ofSeconds(2)
        );
    }

    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        result.createContext("/api/v1/knowledge/query", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        result.start();
        return result;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
