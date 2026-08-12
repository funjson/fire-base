package dev.infinityknowledge.agent.client;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Agent 知识检索请求；租户和主体只从访问令牌取得。 */
public record KnowledgeSearchRequest(
        String query,
        Set<String> spaceIds,
        int topK,
        Map<String, String> filters
) {
    private static final Set<String> SUPPORTED_FILTERS = Set.of("language", "sourceType");

    /** 校验与服务端一致的公开边界，避免发送确定无效的调用。 */
    public KnowledgeSearchRequest {
        Objects.requireNonNull(query, "query must not be null");
        query = query.trim();
        if (query.isEmpty() || query.length() > 16_000) {
            throw new IllegalArgumentException("query must contain 1..16000 characters");
        }
        spaceIds = Set.copyOf(Objects.requireNonNull(spaceIds, "spaceIds must not be null"));
        if (spaceIds.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 64)) {
            throw new IllegalArgumentException("spaceIds must contain 1..64 characters");
        }
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        filters = Map.copyOf(Objects.requireNonNull(filters, "filters must not be null"));
        if (!SUPPORTED_FILTERS.containsAll(filters.keySet())
                || filters.entrySet().stream().anyMatch(entry -> entry.getValue() == null
                || entry.getValue().isBlank() || entry.getValue().length() > 128)) {
            throw new IllegalArgumentException("filters contain unsupported or invalid values");
        }
    }
}
