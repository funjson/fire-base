package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Elasticsearch keyword-index settings.
 */
@ConfigurationProperties(prefix = "infinity.knowledge.keyword.elasticsearch")
public record ElasticsearchProperties(
        boolean enabled,
        String endpoint,
        String indexName,
        String apiKey,
        String username,
        String password,
        Duration connectTimeout,
        Duration requestTimeout
) {

    /**
     * Applies safe local defaults and validates mutually exclusive credentials.
     */
    public ElasticsearchProperties {
        endpoint = defaultText(endpoint, "http://localhost:9200");
        indexName = defaultText(indexName, "knowledge_chunks_v1");
        apiKey = empty(apiKey);
        username = empty(username);
        password = empty(password);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
        if (!apiKey.isEmpty() && !username.isEmpty()) {
            throw new IllegalArgumentException(
                    "Elasticsearch apiKey and basic credentials are mutually exclusive"
            );
        }
        if (username.isEmpty() != password.isEmpty()) {
            throw new IllegalArgumentException(
                    "Elasticsearch username and password must be configured together"
            );
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String empty(String value) {
        return value == null ? "" : value.strip();
    }
}
