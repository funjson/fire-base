package dev.infinityknowledge.provider.zhipu;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal GLM JSON-mode client shared by source-backed graph and Wiki compilation.
 *
 * <p>The client deliberately exposes only a system instruction and a bounded governed input.
 * Provider-specific response types do not cross the adapter boundary.</p>
 */
public final class ZhipuJsonGenerationClient {
    private final ZhipuGenerationConfig config;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final RetrySleeper sleeper;

    public ZhipuJsonGenerationClient(
            ZhipuGenerationConfig config,
            HttpClient httpClient,
            JsonMapper jsonMapper,
            RetrySleeper sleeper
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    /** Sends one bounded JSON-mode request and returns the parsed assistant content. */
    public JsonNode generate(String instruction, String input) {
        instruction = requireText(instruction, "instruction", 32_000);
        input = requireText(input, "input", config.maxInputCharacters());
        Duration backoff = config.initialBackoff();
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request(instruction, input),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parse(response.body());
                }
                if (!retryable(response.statusCode()) || attempt == config.maxAttempts()) {
                    throw new GenerationProviderException(
                            "Zhipu generation request failed with HTTP " + response.statusCode()
                    );
                }
            } catch (IOException networkFailure) {
                if (attempt == config.maxAttempts()) {
                    throw new GenerationProviderException(
                            "Zhipu generation network request failed",
                            networkFailure
                    );
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GenerationProviderException(
                        "Zhipu generation request was interrupted",
                        interrupted
                );
            }
            sleeper.sleep(backoff);
            backoff = backoff.multipliedBy(2);
        }
        throw new GenerationProviderException("Zhipu generation retry budget exhausted");
    }

    /** Returns the configured source budget so compilers can create bounded batches. */
    public int maximumInputCharacters() {
        return config.maxInputCharacters();
    }

    private HttpRequest request(String instruction, String input) {
        Map<String, Object> payload = Map.of(
                "model", config.model(),
                "messages", List.of(
                        Map.of("role", "system", "content", instruction),
                        Map.of("role", "user", "content", input)
                ),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.1D,
                "max_tokens", config.maxOutputTokens(),
                "stream", false
        );
        final String body;
        try {
            body = jsonMapper.writeValueAsString(payload);
        } catch (JacksonException serializationFailure) {
            throw new GenerationProviderException(
                    "Unable to serialize Zhipu generation request",
                    serializationFailure
            );
        }
        return HttpRequest.newBuilder(config.endpoint())
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private JsonNode parse(String body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new GenerationProviderException(
                        "Zhipu generation response does not contain a choice"
                );
            }
            String content = choices.get(0).path("message").path("content").asString("");
            if (content.isBlank()) {
                throw new GenerationProviderException(
                        "Zhipu generation response content is blank"
                );
            }
            return jsonMapper.readTree(content);
        } catch (JacksonException parseFailure) {
            throw new GenerationProviderException(
                    "Unable to parse Zhipu generation response",
                    parseFailure
            );
        }
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain between 1 and " + maximumLength + " characters"
            );
        }
        return normalized;
    }
}
