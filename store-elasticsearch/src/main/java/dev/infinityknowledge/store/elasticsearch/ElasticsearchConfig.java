package dev.infinityknowledge.store.elasticsearch;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable Elasticsearch connection and index settings.
 */
public record ElasticsearchConfig(
        URI endpoint,
        String indexName,
        String authorizationHeader,
        Duration requestTimeout
) {

    /**
     * Validates externally supplied connection settings.
     */
    public ElasticsearchConfig {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint must use http or https");
        }
        indexName = required(indexName, "indexName");
        if (!indexName.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalArgumentException("indexName must be a lowercase Elasticsearch name");
        }
        authorizationHeader = Objects.requireNonNull(
                authorizationHeader,
                "authorizationHeader must not be null"
        ).strip();
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
