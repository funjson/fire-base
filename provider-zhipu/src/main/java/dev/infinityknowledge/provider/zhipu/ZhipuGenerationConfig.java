package dev.infinityknowledge.provider.zhipu;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Bounded configuration for structured GLM knowledge compilation calls.
 *
 * @param endpoint complete chat-completions endpoint
 * @param apiKey provider credential
 * @param model model identifier
 * @param requestTimeout per-attempt network timeout
 * @param maxAttempts bounded retry attempts including the first call
 * @param initialBackoff first retry delay
 * @param maxInputCharacters maximum governed source characters sent in one request
 * @param maxOutputTokens maximum model output budget
 */
public record ZhipuGenerationConfig(
        URI endpoint,
        String apiKey,
        String model,
        Duration requestTimeout,
        int maxAttempts,
        Duration initialBackoff,
        int maxInputCharacters,
        int maxOutputTokens
) {

    /** Validates network, credential and request-budget settings. */
    public ZhipuGenerationConfig {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint must use HTTP or HTTPS");
        }
        apiKey = DomainChecks.requiredText(apiKey, "apiKey", 4096);
        model = DomainChecks.requiredText(model, "model", 128);
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (requestTimeout.isZero() || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive and at most five minutes"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        if (initialBackoff.isNegative() || initialBackoff.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException(
                    "initialBackoff must be between zero and ten seconds"
            );
        }
        if (maxInputCharacters < 1_000 || maxInputCharacters > 500_000) {
            throw new IllegalArgumentException(
                    "maxInputCharacters must be between 1000 and 500000"
            );
        }
        if (maxOutputTokens < 256 || maxOutputTokens > 32_768) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be between 256 and 32768"
            );
        }
    }
}
