package dev.infinityknowledge.provider.zhipu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.wiki.PageSynthesisProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhipuKnowledgeGenerationTest {
    private static final TenantId TENANT = new TenantId("demo");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void pageProviderRequiresDeclaredInlineCitations() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = server(exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            respond(exchange, 200, completion("""
                    {"summary":"订单服务依赖 Redis。",
                     "markdown":"# 订单服务 - 依赖 Redis。[source:chunk-a]",
                     "usedReferenceIds":["chunk-a"]}
                    """));
        });
        PageSynthesisProvider provider = new ZhipuPageSynthesisProvider(client("secret-key"));

        var result = provider.synthesize(new PageSynthesisProvider.SynthesisRequest(
                "订单服务",
                List.of(new PageSynthesisProvider.SourceExcerpt(
                        "chunk-a",
                        "架构 / 依赖",
                        "订单服务依赖 Redis。",
                        90
                ))
        ));

        assertEquals(List.of("chunk-a"), result.usedReferenceIds());
        assertTrue(result.markdown().contains("[source:chunk-a]"));
        assertTrue(requestBody.get().contains("json_object"));
        assertFalse(requestBody.get().contains("secret-key"));
    }

    @Test
    void graphExtractorBindsEveryAssertionToSuppliedChunk() throws IOException {
        ProjectionSource source = source();
        UUID chunkId = source.chunks().getFirst().id();
        server = server(exchange -> respond(exchange, 200, completion("""
                {
                  "entities":[
                    {"ref":"order","type":"SERVICE","name":"Order Service",
                     "aliases":["订单服务"],"sourceChunkIds":["%s"],"confidence":0.95},
                    {"ref":"redis","type":"DATABASE","name":"Redis",
                     "aliases":[],"sourceChunkIds":["%s"],"confidence":0.93}
                  ],
                  "events":[],
                  "relations":[
                    {"sourceRef":"order","targetRef":"redis","type":"DEPENDS_ON",
                     "sourceChunkIds":["%s"],"confidence":0.92}
                  ]
                }
                """.formatted(chunkId, chunkId, chunkId))));

        var graph = new ZhipuGraphExtractor(client("secret-key")).extract(source);

        assertEquals(2, graph.entities().size());
        assertEquals(1, graph.relations().size());
        assertEquals(chunkId, graph.relations().getFirst().provenance().getFirst().chunkId());
        assertEquals("DEPENDS_ON", graph.relations().getFirst().type());
    }

    @Test
    void graphExtractorRejectsUnknownChunkWithoutLeakingSource() throws IOException {
        ProjectionSource source = source();
        server = server(exchange -> respond(exchange, 200, completion("""
                {"entities":[{"ref":"order","type":"SERVICE","name":"Order Service",
                 "aliases":[],"sourceChunkIds":["00000000-0000-0000-0000-000000000000"],
                 "confidence":0.9}],"events":[],"relations":[]}
                """)));

        GenerationProviderException failure = assertThrows(
                GenerationProviderException.class,
                () -> new ZhipuGraphExtractor(client("secret-key")).extract(source)
        );

        assertFalse(failure.getMessage().contains("Order Service depends on Redis"));
        assertFalse(failure.getMessage().contains("secret-key"));
    }

    private ZhipuJsonGenerationClient client(String apiKey) {
        return new ZhipuJsonGenerationClient(
                new ZhipuGenerationConfig(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"),
                        apiKey,
                        "glm-test",
                        Duration.ofSeconds(2),
                        1,
                        Duration.ZERO,
                        20_000,
                        2_048
                ),
                HttpClient.newHttpClient(),
                JsonMapper.builder().build(),
                ignored -> {
                }
        );
    }

    private static ProjectionSource source() {
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                TENANT,
                SPACE,
                "Order architecture",
                new SourceDescriptor(
                        "api-upload:engineering",
                        SourceType.UPLOAD,
                        "order.md",
                        "https://knowledge.example/order.md",
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                90,
                Map.of(),
                now,
                now
        );
        KnowledgeChunk chunk = new KnowledgeChunk(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                documentId,
                revisionId,
                List.of(UUID.randomUUID()),
                0,
                List.of("Architecture"),
                "Order Service depends on Redis.",
                "hash",
                Map.of()
        );
        return new ProjectionSource(document, List.of(chunk));
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
