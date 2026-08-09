package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * GLM embedding and immutable vector-generation settings.
 */
@ConfigurationProperties(prefix = "infinity.knowledge.embedding")
public record EmbeddingProperties(
        String provider,
        String model,
        int dimensions,
        String generation,
        URI endpoint,
        String apiKey,
        Duration requestTimeout,
        int maxBatchSize,
        int maxAttempts,
        Duration initialBackoff,
        String proxyHost,
        int proxyPort
) {

    /**
     * Applies safe operational defaults while leaving credentials external.
     */
    public EmbeddingProperties {
        provider = defaultText(provider, "zhipu");
        model = defaultText(model, "embedding-3");
        generation = defaultText(generation, "v1");
        endpoint = endpoint == null
                ? URI.create("https://open.bigmodel.cn/api/paas/v4/embeddings")
                : endpoint;
        apiKey = apiKey == null ? "" : apiKey.strip();
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        initialBackoff = initialBackoff == null ? Duration.ofMillis(250) : initialBackoff;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (dimensions < 1) {
            dimensions = 2_048;
        }
        if (maxBatchSize < 1) {
            maxBatchSize = 32;
        }
        if (maxAttempts < 1) {
            maxAttempts = 3;
        }
        if (!proxyHost.isEmpty() && (proxyPort < 1 || proxyPort > 65_535)) {
            throw new IllegalArgumentException(
                    "embedding proxyPort must be valid when proxyHost is configured"
            );
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
