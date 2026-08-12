package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** Bounded Wiki compilation settings; credentials remain external to source control. */
@ConfigurationProperties(prefix = "infinity.knowledge.wiki")
public record WikiProperties(
        boolean generativeEnabled,
        URI endpoint,
        String apiKey,
        String model,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        int maxInputCharacters,
        int maxOutputTokens,
        double minimumSourceCoverage,
        String proxyHost,
        int proxyPort
) {
    public WikiProperties {
        endpoint = endpoint == null
                ? URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions")
                : endpoint;
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? "glm-4-flash" : model.strip();
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
        initialBackoff = initialBackoff == null ? Duration.ofMillis(300) : initialBackoff;
        maxAttempts = maxAttempts < 1 ? 2 : maxAttempts;
        maxInputCharacters = maxInputCharacters < 1 ? 120_000 : maxInputCharacters;
        maxOutputTokens = maxOutputTokens < 1 ? 4_096 : maxOutputTokens;
        minimumSourceCoverage = minimumSourceCoverage <= 0.0D
                ? 0.5D : minimumSourceCoverage;
        proxyHost = proxyHost == null ? "" : proxyHost.strip();
        if (!proxyHost.isEmpty() && (proxyPort < 1 || proxyPort > 65_535)) {
            throw new IllegalArgumentException(
                    "wiki proxyPort must be valid when proxyHost is configured"
            );
        }
    }
}
