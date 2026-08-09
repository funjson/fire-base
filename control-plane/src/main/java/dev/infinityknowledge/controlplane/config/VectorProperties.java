package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Milvus vector-index settings.
 */
@ConfigurationProperties(prefix = "infinity.knowledge.vector")
public record VectorProperties(
        boolean enabled,
        String uri,
        String token,
        String collectionPrefix,
        int partitionCount,
        Duration connectTimeout,
        Duration rpcDeadline
) {

    /**
     * Applies local-development defaults and validates resource bounds.
     */
    public VectorProperties {
        uri = defaultText(uri, "http://localhost:19530");
        token = token == null ? "" : token.strip();
        collectionPrefix = defaultText(collectionPrefix, "knowledge_chunks");
        if (partitionCount < 1) {
            partitionCount = 64;
        }
        if (partitionCount > 4_096) {
            throw new IllegalArgumentException("partitionCount must not exceed 4096");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        rpcDeadline = rpcDeadline == null ? Duration.ofSeconds(30) : rpcDeadline;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
