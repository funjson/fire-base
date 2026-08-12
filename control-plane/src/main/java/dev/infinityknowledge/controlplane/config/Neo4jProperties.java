package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** Connection, traversal and bounded GLM extraction settings for the optional graph channel. */
@ConfigurationProperties(prefix = "infinity.knowledge.graph.neo4j")
public record Neo4jProperties(
        boolean enabled,
        String uri,
        String username,
        String password,
        String database,
        int maxHops,
        int maxResults,
        URI extractionEndpoint,
        String extractionApiKey,
        String extractionModel,
        Duration extractionTimeout,
        int extractionMaxAttempts,
        Duration extractionInitialBackoff,
        int extractionMaxInputCharacters,
        int extractionMaxOutputTokens,
        String proxyHost,
        int proxyPort
) {
    public Neo4jProperties {
        uri = defaultText(uri, "bolt://localhost:7687");
        username = defaultText(username, "neo4j");
        password = password == null ? "" : password.strip();
        database = defaultText(database, "neo4j");
        maxHops = maxHops < 1 ? 2 : maxHops;
        maxResults = maxResults < 1 ? 50 : maxResults;
        extractionEndpoint = extractionEndpoint == null
                ? URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions")
                : extractionEndpoint;
        extractionApiKey = extractionApiKey == null ? "" : extractionApiKey.strip();
        extractionModel = defaultText(extractionModel, "glm-4.5-flash");
        extractionTimeout = extractionTimeout == null
                ? Duration.ofSeconds(90) : extractionTimeout;
        extractionMaxAttempts = extractionMaxAttempts < 1 ? 2 : extractionMaxAttempts;
        extractionInitialBackoff = extractionInitialBackoff == null
                ? Duration.ofMillis(300) : extractionInitialBackoff;
        extractionMaxInputCharacters = extractionMaxInputCharacters < 1
                ? 120_000 : extractionMaxInputCharacters;
        extractionMaxOutputTokens = extractionMaxOutputTokens < 1
                ? 8_192 : extractionMaxOutputTokens;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (enabled && password.isBlank()) {
            throw new IllegalArgumentException("Neo4j password is required when graph is enabled");
        }
        if (!proxyHost.isEmpty() && (proxyPort < 1 || proxyPort > 65_535)) {
            throw new IllegalArgumentException(
                    "graph proxyPort must be valid when proxyHost is configured"
            );
        }
    }

    /** Maximum wall-clock budget consumed by all configured extraction attempts and backoffs. */
    public Duration maximumExtractionDuration() {
        Duration total = extractionTimeout.multipliedBy(extractionMaxAttempts);
        Duration backoff = extractionInitialBackoff;
        for (int attempt = 1; attempt < extractionMaxAttempts; attempt++) {
            total = total.plus(backoff);
            backoff = backoff.multipliedBy(2);
        }
        return total;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
