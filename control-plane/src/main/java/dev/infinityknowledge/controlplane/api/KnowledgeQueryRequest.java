package dev.infinityknowledge.controlplane.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Set;

/**
 * 定义 Agent 调用知识检索 API 的请求体。
 *
 * @param query 查询文本
 * @param spaceIds 可选知识空间
 * @param topK 可选证据上限
 * @param filters 业务元数据过滤
 */
public record KnowledgeQueryRequest(
        @NotBlank @Size(max = 16_000) String query,
        Set<String> spaceIds,
        @Min(1) @Max(100) Integer topK,
        Map<String, String> filters
) {
    private static final Set<String> SUPPORTED_FILTERS = Set.of(
            "language",
            "sourceType"
    );

    /**
     * 将可选集合规范化为空集合，并拒绝运行时无法一致执行的过滤条件。
     */
    public KnowledgeQueryRequest {
        if (spaceIds != null && spaceIds.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 64)) {
            throw new IllegalArgumentException(
                    "spaceIds must contain 1..64 characters"
            );
        }
        spaceIds = spaceIds == null ? Set.of() : Set.copyOf(spaceIds);
        if (filters != null && filters.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getValue() == null
                        || entry.getValue().isBlank()
                        || entry.getValue().length() > 128)) {
            throw new IllegalArgumentException(
                    "filter values must contain 1..128 characters"
            );
        }
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        if (!SUPPORTED_FILTERS.containsAll(filters.keySet())) {
            throw new IllegalArgumentException(
                    "filters only support sourceType and language"
            );
        }
    }
}
