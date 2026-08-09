package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 使用显式开关验证真实智谱 Embedding 协议和凭据。
 */
@EnabledIfEnvironmentVariable(named = "RUN_ZHIPU_TESTS", matches = "true")
class ZhipuEmbeddingProviderIT {

    /**
     * 发送一条无敏感测试文本并验证 2048 维响应。
     */
    @Test
    void embedsTextAgainstRealApi() {
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ZHIPU_API_KEY is required");
        }
        ZhipuEmbeddingProvider provider = new ZhipuEmbeddingProvider(
                new ZhipuEmbeddingConfig(
                        URI.create("https://open.bigmodel.cn/api/paas/v4/embeddings"),
                        apiKey,
                        Duration.ofSeconds(20),
                        64,
                        3,
                        Duration.ofMillis(200)
                ),
                httpClient(),
                JsonMapper.builder().build(),
                new ThreadRetrySleeper()
        );

        var vectors = provider.embed(
                List.of("Infinity Knowledge embedding integration test"),
                new EmbeddingSpec("zhipu", "embedding-3", 2_048)
        );

        assertEquals(1, vectors.size());
        assertEquals(2_048, vectors.getFirst().values().size());
    }

    private static HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        String proxyHost = System.getenv("KNOWLEDGE_EMBEDDING_PROXY_HOST");
        if (proxyHost != null && !proxyHost.isBlank()) {
            String configuredPort = System.getenv("KNOWLEDGE_EMBEDDING_PROXY_PORT");
            int proxyPort = configuredPort == null || configuredPort.isBlank()
                    ? 7_890 : Integer.parseInt(configuredPort.strip());
            builder.proxy(ProxySelector.of(
                    new InetSocketAddress(proxyHost.strip(), proxyPort)
            ));
        }
        return builder.build();
    }
}
