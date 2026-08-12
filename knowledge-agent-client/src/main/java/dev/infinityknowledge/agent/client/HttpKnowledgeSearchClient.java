package dev.infinityknowledge.agent.client;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 JDK HTTP Client 调用版本化 Knowledge API 的轻量客户端。 */
public final class HttpKnowledgeSearchClient implements KnowledgeSearchClient {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private final URI queryEndpoint;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final AccessTokenProvider tokenProvider;
    private final Duration timeout;

    public HttpKnowledgeSearchClient(
            URI baseUri,
            HttpClient httpClient,
            JsonMapper jsonMapper,
            AccessTokenProvider tokenProvider,
            Duration timeout
    ) {
        Objects.requireNonNull(baseUri, "baseUri must not be null");
        URI normalized = baseUri.toString().endsWith("/")
                ? baseUri
                : URI.create(baseUri + "/");
        this.queryEndpoint = normalized.resolve("api/v1/knowledge/query");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public KnowledgeSearchResponse search(
            UUID requestId,
            KnowledgeSearchRequest request
    ) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        String token = tokenProvider.accessToken();
        if (token == null || token.isBlank() || containsControlCharacter(token)) {
            throw new KnowledgeClientException(
                    0,
                    "ACCESS_TOKEN_UNAVAILABLE",
                    "No valid access token is available",
                    requestId.toString(),
                    false
            );
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(queryEndpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(REQUEST_ID_HEADER, requestId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(serialize(request, requestId)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            return parseResponse(response, requestId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KnowledgeClientException(
                    0,
                    "KNOWLEDGE_CALL_INTERRUPTED",
                    "Knowledge retrieval was cancelled",
                    requestId.toString(),
                    false,
                    interrupted
            );
        } catch (IOException transportFailure) {
            throw new KnowledgeClientException(
                    0,
                    "KNOWLEDGE_SERVICE_UNAVAILABLE",
                    "Knowledge service is unavailable",
                    requestId.toString(),
                    true,
                    transportFailure
            );
        }
    }

    private String serialize(KnowledgeSearchRequest request, UUID requestId) {
        try {
            return jsonMapper.writeValueAsString(request);
        } catch (JacksonException invalidRequest) {
            throw new KnowledgeClientException(
                    0,
                    "KNOWLEDGE_REQUEST_SERIALIZATION_FAILED",
                    "Knowledge request cannot be serialized",
                    requestId.toString(),
                    false,
                    invalidRequest
            );
        }
    }

    private KnowledgeSearchResponse parseResponse(
            HttpResponse<String> response,
            UUID originalRequestId
    ) {
        String headerRequestId = response.headers()
                .firstValue(REQUEST_ID_HEADER)
                .orElse(originalRequestId.toString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            ApiErrorPayload error = parseError(response.body()).orElse(null);
            String code = error == null ? "KNOWLEDGE_HTTP_" + response.statusCode() : error.code();
            String message = error == null
                    ? "Knowledge service rejected the request"
                    : error.message();
            String correlatedRequestId = error == null || error.requestId() == null
                    ? headerRequestId
                    : error.requestId();
            throw new KnowledgeClientException(
                    response.statusCode(),
                    code,
                    message,
                    correlatedRequestId,
                    retryable(response.statusCode())
            );
        }
        try {
            KnowledgeSearchResponse result = jsonMapper.readValue(
                    response.body(),
                    KnowledgeSearchResponse.class
            );
            if (!originalRequestId.equals(result.requestId())
                    || !originalRequestId.toString().equals(headerRequestId)) {
                throw protocolFailure(originalRequestId);
            }
            return result;
        } catch (JacksonException invalidResponse) {
            throw new KnowledgeClientException(
                    response.statusCode(),
                    "KNOWLEDGE_PROTOCOL_ERROR",
                    "Knowledge service returned an invalid response",
                    headerRequestId,
                    false,
                    invalidResponse
            );
        }
    }

    private Optional<ApiErrorPayload> parseError(String body) {
        try {
            return Optional.of(jsonMapper.readValue(body, ApiErrorPayload.class));
        } catch (JacksonException invalidError) {
            return Optional.empty();
        }
    }

    private KnowledgeClientException protocolFailure(UUID requestId) {
        return new KnowledgeClientException(
                200,
                "KNOWLEDGE_PROTOCOL_ERROR",
                "Knowledge response correlation does not match the request",
                requestId.toString(),
                false
        );
    }

    private boolean retryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7F);
    }

    private record ApiErrorPayload(String code, String message, String requestId) {
    }
}
