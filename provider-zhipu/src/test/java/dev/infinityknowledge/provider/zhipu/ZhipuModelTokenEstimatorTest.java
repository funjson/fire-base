package dev.infinityknowledge.provider.zhipu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.infinityknowledge.spi.model.ModelMessage;
import dev.infinityknowledge.spi.model.ModelTokenEstimateRequest;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证智谱 Prompt Token 计数协议、模型边界、重试和敏感信息保护。 */
class ZhipuModelTokenEstimatorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void countsCompleteGlm51PromptWithBearerAuthentication() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = server(exchange -> {
            requestBody.set(readBody(exchange));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"usage\":{\"prompt_tokens\":37,\"total_tokens\":37}}");
        });

        var estimate = estimator("secret-key", 1, ignored -> {
        }).estimate(request("glm-5.1", "用户问题"));

        assertEquals(37, estimate.promptTokens());
        assertTrue(estimate.exact());
        assertEquals(ZhipuModelTokenEstimator.VERSION, estimate.estimatorVersion());
        assertEquals("Bearer secret-key", authorization.get());
        assertTrue(requestBody.get().contains("\"model\":\"glm-5.1\""));
        assertTrue(requestBody.get().contains("\"role\":\"system\""));
        assertTrue(requestBody.get().contains("\"role\":\"user\""));
    }

    @Test
    void supportsGlm52AndRejectsModelsOutsideTheApprovedPair() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "{\"usage\":{\"prompt_tokens\":8}}");
        });
        ZhipuModelTokenEstimator estimator = estimator("secret-key", 1, ignored -> {
        });

        assertEquals(8, estimator.estimate(request("glm-5.2", "query")).promptTokens());
        assertThrows(
                IllegalArgumentException.class,
                () -> estimator.estimate(request("glm-4.5-flash", "query"))
        );
        assertEquals(1, calls.get());
    }

    @Test
    void retriesRateLimitWithinBoundedBudget() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        server = server(exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 429, "{\"error\":{\"message\":\"busy\"}}");
                return;
            }
            respond(exchange, 200, "{\"usage\":{\"prompt_tokens\":11}}");
        });

        int count = estimator("secret-key", 2, ignored -> sleeps.incrementAndGet())
                .estimate(request("glm-5.2", "query"))
                .promptTokens();

        assertEquals(11, count);
        assertEquals(2, calls.get());
        assertEquals(1, sleeps.get());
    }

    @Test
    void preservesInterruptFlagWhenRetryWaitingIsInterrupted() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 429, "{\"error\":{\"message\":\"busy\"}}");
        });
        RetrySleeper interruptedSleeper = ignored -> {
            Thread.currentThread().interrupt();
            new ThreadRetrySleeper().sleep(Duration.ofMillis(1));
        };

        try {
            TokenizerProviderException failure = assertThrows(
                    TokenizerProviderException.class,
                    () -> estimator("secret-key", 2, interruptedSleeper)
                            .estimate(request("glm-5.2", "query"))
            );

            assertEquals("Zhipu tokenizer retry wait failed", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
        } finally {
            // 避免中断标记污染执行同一工作线程的其他 JUnit 用例。
            Thread.interrupted();
        }
    }

    @Test
    void rejectsInvalidUsageWithoutLeakingPromptKeyOrResponse() throws IOException {
        String prompt = "private-customer-query";
        String responseSecret = "provider-raw-secret";
        server = server(exchange -> respond(
                exchange,
                200,
                "{\"usage\":{\"prompt_tokens\":-1},\"debug\":\"" + responseSecret + "\"}"
        ));

        TokenizerProviderException failure = assertThrows(
                TokenizerProviderException.class,
                () -> estimator("secret-key", 1, ignored -> {
                }).estimate(request("glm-5.2", prompt))
        );

        assertFalse(failure.getMessage().contains(prompt));
        assertFalse(failure.getMessage().contains("secret-key"));
        assertFalse(failure.getMessage().contains(responseSecret));
    }

    @Test
    void rejectsFractionalAndOverflowingTokenCounts() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            String body = calls.incrementAndGet() == 1
                    ? "{\"usage\":{\"prompt_tokens\":1.5}}"
                    : "{\"usage\":{\"prompt_tokens\":9223372036854775808}}";
            respond(exchange, 200, body);
        });
        ZhipuModelTokenEstimator estimator = estimator("secret-key", 1, ignored -> {
        });

        assertThrows(
                TokenizerProviderException.class,
                () -> estimator.estimate(request("glm-5.2", "first"))
        );
        assertThrows(
                TokenizerProviderException.class,
                () -> estimator.estimate(request("glm-5.2", "second"))
        );
        assertEquals(2, calls.get());
    }

    @Test
    void nonRetryableHttpFailureDoesNotExposeProviderBody() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 400, "customer-query-was-invalid");
        });

        TokenizerProviderException failure = assertThrows(
                TokenizerProviderException.class,
                () -> estimator("secret-key", 3, ignored -> {
                }).estimate(request("glm-5.2", "customer-query"))
        );

        assertEquals(1, calls.get());
        assertEquals("Zhipu tokenizer request failed with HTTP 400", failure.getMessage());
    }

    private ZhipuModelTokenEstimator estimator(
            String apiKey,
            int attempts,
            RetrySleeper sleeper
    ) {
        return new ZhipuModelTokenEstimator(
                new ZhipuTokenizerConfig(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                                + "/tokenizer"),
                        apiKey,
                        Duration.ofSeconds(2),
                        attempts,
                        Duration.ZERO,
                        10_000
                ),
                HttpClient.newHttpClient(),
                JsonMapper.builder().build(),
                sleeper
        );
    }

    private static ModelTokenEstimateRequest request(String model, String userContent) {
        return new ModelTokenEstimateRequest(
                "zhipu",
                model,
                List.of(
                        new ModelMessage(ModelMessage.Role.SYSTEM, "system instruction"),
                        new ModelMessage(ModelMessage.Role.USER, userContent)
                )
        );
    }

    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/tokenizer", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        value.start();
        return value;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
