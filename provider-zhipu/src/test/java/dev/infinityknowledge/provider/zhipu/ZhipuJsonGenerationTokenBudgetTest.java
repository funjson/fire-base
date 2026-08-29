package dev.infinityknowledge.provider.zhipu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.infinityknowledge.spi.model.ModelTokenEstimate;
import dev.infinityknowledge.spi.model.ModelTokenEstimateRequest;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证结构化生成在发送模型请求前校验完整同模型 Prompt Token 预算。 */
class ZhipuJsonGenerationTokenBudgetTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedPromptBeforeGenerationNetworkCall() throws IOException {
        AtomicInteger tokenizerCalls = new AtomicInteger();
        AtomicInteger generationCalls = new AtomicInteger();
        server = server(
                exchange -> {
                    tokenizerCalls.incrementAndGet();
                    respond(exchange, 200, "{\"usage\":{\"prompt_tokens\":513}}");
                },
                exchange -> {
                    generationCalls.incrementAndGet();
                    respond(exchange, 200, completion());
                }
        );
        ZhipuJsonGenerationClient client = client("glm-5.2", 512, realEstimator());

        GenerationProviderException failure = assertThrows(
                GenerationProviderException.class,
                () -> client.generate("system-instruction", "private-user-input")
        );

        assertEquals(
                "Zhipu generation prompt exceeds the configured token budget",
                failure.getMessage()
        );
        assertEquals(1, tokenizerCalls.get());
        assertEquals(0, generationCalls.get());
        assertFalse(failure.getMessage().contains("private-user-input"));
    }

    @Test
    void acceptsPromptAtBudgetAndUsesOneModelForTokenizerAndGeneration() throws IOException {
        AtomicReference<String> tokenizerBody = new AtomicReference<>();
        AtomicReference<String> generationBody = new AtomicReference<>();
        server = server(
                exchange -> {
                    tokenizerBody.set(readBody(exchange));
                    respond(exchange, 200, "{\"usage\":{\"prompt_tokens\":512}}");
                },
                exchange -> {
                    generationBody.set(readBody(exchange));
                    respond(exchange, 200, completion());
                }
        );

        JsonNode result = client("glm-5.2", 512, realEstimator())
                .generate("system-instruction", "user-input");

        JsonNode tokenizerRequest = mapper.readTree(tokenizerBody.get());
        JsonNode generationRequest = mapper.readTree(generationBody.get());
        assertTrue(result.path("ok").asBoolean());
        assertEquals("glm-5.2", tokenizerRequest.path("model").asString());
        assertEquals(
                tokenizerRequest.path("model").asString(),
                generationRequest.path("model").asString()
        );
        assertEquals(
                "disabled",
                generationRequest.path("thinking").path("type").asString()
        );
    }

    @Test
    void rejectsEstimatorThatCannotCountTheGenerationModel() {
        ModelTokenEstimator estimator = estimator(new AtomicReference<>(), 1, true);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ZhipuJsonGenerationClient(
                        config(
                                "glm-4.5-flash",
                                512,
                                URI.create("https://example.invalid/chat")
                        ),
                        HttpClient.newHttpClient(),
                        mapper,
                        ignored -> {
                        },
                        estimator
                )
        );
    }

    @Test
    void rejectsInexactEstimateInsteadOfFallingBackToCharacters() {
        ZhipuJsonGenerationClient client = new ZhipuJsonGenerationClient(
                config("glm-5.1", 512, URI.create("https://example.invalid/chat")),
                HttpClient.newHttpClient(),
                mapper,
                ignored -> {
                },
                estimator(new AtomicReference<>(), 12, false)
        );

        GenerationProviderException failure = assertThrows(
                GenerationProviderException.class,
                () -> client.generate("system", "input")
        );

        assertEquals(
                "Zhipu generation requires an exact prompt token estimate",
                failure.getMessage()
        );
    }

    private ZhipuJsonGenerationClient client(
            String model,
            int maximumPromptTokens,
            ModelTokenEstimator estimator
    ) {
        return new ZhipuJsonGenerationClient(
                config(model, maximumPromptTokens, endpoint("/chat")),
                HttpClient.newHttpClient(),
                mapper,
                ignored -> {
                },
                estimator
        );
    }

    private ZhipuModelTokenEstimator realEstimator() {
        return new ZhipuModelTokenEstimator(
                new ZhipuTokenizerConfig(
                        endpoint("/tokenizer"),
                        "secret-key",
                        Duration.ofSeconds(2),
                        1,
                        Duration.ZERO,
                        10_000
                ),
                HttpClient.newHttpClient(),
                mapper,
                ignored -> {
                }
        );
    }

    private static ZhipuGenerationConfig config(
            String model,
            int maximumPromptTokens,
            URI endpoint
    ) {
        return new ZhipuGenerationConfig(
                endpoint,
                "secret-key",
                model,
                Duration.ofSeconds(2),
                1,
                Duration.ZERO,
                20_000,
                maximumPromptTokens,
                1_024
        );
    }

    private static ModelTokenEstimator estimator(
            AtomicReference<ModelTokenEstimateRequest> measured,
            int tokens,
            boolean exact
    ) {
        return new ModelTokenEstimator() {
            @Override
            public String providerId() {
                return "zhipu";
            }

            @Override
            public Set<String> supportedModelIds() {
                return Set.of("glm-5.1", "glm-5.2");
            }

            @Override
            public String version() {
                return "test-v1";
            }

            @Override
            public Duration maximumLatency() {
                return Duration.ZERO;
            }

            @Override
            public ModelTokenEstimate estimate(ModelTokenEstimateRequest request) {
                measured.set(request);
                return new ModelTokenEstimate(tokens, exact, version());
            }
        };
    }

    private HttpServer server(
            ThrowingHandler tokenizerHandler,
            ThrowingHandler generationHandler
    ) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/tokenizer", exchange -> handle(exchange, tokenizerHandler));
        value.createContext("/chat", exchange -> handle(exchange, generationHandler));
        value.start();
        return value;
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void handle(HttpExchange exchange, ThrowingHandler handler)
            throws IOException {
        try {
            handler.handle(exchange);
        } finally {
            exchange.close();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String completion() {
        return "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}";
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
