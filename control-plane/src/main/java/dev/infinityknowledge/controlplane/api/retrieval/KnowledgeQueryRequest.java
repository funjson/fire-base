package dev.infinityknowledge.controlplane.api.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义 Agent 调用知识检索 API 的请求体。
 *
 * @param query 查询文本
 * @param spaceIds 可选知识空间
 * @param topK 可选证据上限
 * @param filters 业务元数据过滤
 * @param constraints 允许检索 Chain 确定性放宽或收窄的逻辑约束
 * @param retrievalTarget 可选检索目标
 * @param evidenceRequirements 可选的证据覆盖要求
 * @param configurationOverride 本次请求对 Space 配置的局部覆盖
 * @param testMode 是否作为测试广场执行并隔离指标
 */
public record KnowledgeQueryRequest(
        @NotBlank @Size(max = 16_000) String query,
        Set<String> spaceIds,
        @Min(1) @Max(100) Integer topK,
        Map<String, String> filters,
        RetrievalConstraintsRequest constraints,
        @Size(max = 4_000) String retrievalTarget,
        @Size(max = 32) List<EvidenceRequirementRequest> evidenceRequirements,
        RetrievalConfigurationOverrideRequest configurationOverride,
        Boolean testMode
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
        constraints = constraints == null ? RetrievalConstraintsRequest.empty() : constraints;
        if (!java.util.Collections.disjoint(
                filters.keySet(),
                constraints.relaxableFilters().keySet()
        ) || !java.util.Collections.disjoint(
                filters.keySet(),
                constraints.narrowingFilters().keySet()
        ) || !java.util.Collections.disjoint(
                constraints.relaxableFilters().keySet(),
                constraints.narrowingFilters().keySet()
        )) {
            throw new IllegalArgumentException(
                    "hard, relaxable and narrowing filter keys must not overlap"
            );
        }
        retrievalTarget = retrievalTarget == null ? "" : retrievalTarget.strip();
        evidenceRequirements = evidenceRequirements == null
                ? List.of() : List.copyOf(evidenceRequirements);
        if (evidenceRequirements.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "evidenceRequirements must not contain null values"
            );
        }
        configurationOverride = configurationOverride == null
                ? RetrievalConfigurationOverrideRequest.empty()
                : configurationOverride;
        testMode = Boolean.TRUE.equals(testMode);
    }

    /**
     * 定义初始生效的可放宽过滤和证据过宽时才应用的收窄候选。
     *
     * <p>普通 {@code filters} 不在这里，因其始终是不可放宽的硬约束。</p>
     *
     * @param relaxableFilters 调用方显式允许 RELAX 移除的初始过滤
     * @param narrowingFilters 调用方已提供且允许 NARROW 应用的候选过滤
     */
    public record RetrievalConstraintsRequest(
            Map<String, String> relaxableFilters,
            Map<String, String> narrowingFilters
    ) {
        public RetrievalConstraintsRequest {
            relaxableFilters = normalizeFilters(
                    relaxableFilters,
                    "relaxableFilters"
            );
            narrowingFilters = normalizeFilters(
                    narrowingFilters,
                    "narrowingFilters"
            );
        }

        /** 返回不允许 Chain 修改过滤条件的默认约束。 */
        public static RetrievalConstraintsRequest empty() {
            return new RetrievalConstraintsRequest(Map.of(), Map.of());
        }

        private static Map<String, String> normalizeFilters(
                Map<String, String> values,
                String fieldName
        ) {
            Map<String, String> normalized = values == null ? Map.of() : Map.copyOf(values);
            if (normalized.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null
                            || entry.getValue() == null
                            || entry.getValue().isBlank()
                            || entry.getValue().length() > 128)) {
                throw new IllegalArgumentException(
                        fieldName + " values must contain 1..128 characters"
                );
            }
            if (!SUPPORTED_FILTERS.containsAll(normalized.keySet())) {
                throw new IllegalArgumentException(
                        fieldName + " only supports sourceType and language"
                );
            }
            return normalized;
        }
    }

    /**
     * 定义 Coverage Judge 需要检查的一项证据要求。
     *
     * @param id 单次请求内稳定标识
     * @param description 需要证据支持的事实描述
     */
    public record EvidenceRequirementRequest(
            @NotBlank @Size(max = 64) String id,
            @NotBlank @Size(max = 2_000) String description
    ) {
    }
}
