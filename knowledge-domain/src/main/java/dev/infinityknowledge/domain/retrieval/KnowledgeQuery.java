package dev.infinityknowledge.domain.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.retrieval.configuration.RetrievalConfigurationOverride;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 表示 Agent 或应用提交给知识运行时的受权查询。
 *
 * @param requestId 调用方幂等和关联标识
 * @param principal 已认证主体
 * @param text 用户查询文本
 * @param spaceIds 限定知识空间，空集合表示由策略层计算全部可访问空间
 * @param topK 最大证据数量
 * @param filters 调用方不可放宽的业务元数据硬过滤
 * @param constraints 调用方显式授权放宽或收窄的逻辑约束
 * @param retrievalTarget 可选的检索目标，不承担多意图拆分职责
 * @param evidenceRequirements Coverage Judge 需要逐项覆盖的证据要求
 * @param configurationOverride 单次请求对 Space 配置的强类型覆盖
 * @param purpose 执行用途，用于隔离在线、评测和测试广场指标
 */
public record KnowledgeQuery(
        UUID requestId,
        PrincipalContext principal,
        String text,
        Set<KnowledgeSpaceId> spaceIds,
        int topK,
        Map<String, String> filters,
        RetrievalConstraintInput constraints,
        String retrievalTarget,
        List<EvidenceRequirement> evidenceRequirements,
        RetrievalConfigurationOverride configurationOverride,
        RetrievalObservationPurpose purpose
) {
    private static final Set<String> SUPPORTED_FILTERS = Set.of(
            "language",
            "sourceType"
    );

    /**
     * 创建不覆盖 Space 配置、也不请求 Coverage 的普通在线独立查询。
     *
     * <p>该工厂避免调用方用位置参数重复拼装空值，同时不会隐藏测试广场或评测用途。</p>
     */
    public static KnowledgeQuery online(
            UUID requestId,
            PrincipalContext principal,
            String text,
            Set<KnowledgeSpaceId> spaceIds,
            int topK,
            Map<String, String> filters
    ) {
        return new KnowledgeQuery(
                requestId,
                principal,
                text,
                spaceIds,
                topK,
                filters,
                RetrievalConstraintInput.empty(),
                "",
                List.of(),
                RetrievalConfigurationOverride.empty(),
                RetrievalObservationPurpose.ONLINE
        );
    }

    /**
     * 校验独立查询、证据要求、返回数量和调用期间不可变的过滤条件。
     */
    public KnowledgeQuery {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        text = DomainChecks.requiredText(text, "query text", 16_000);
        spaceIds = Set.copyOf(Objects.requireNonNull(spaceIds, "spaceIds must not be null"));
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
        Objects.requireNonNull(filters, "filters must not be null");
        if (filters.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getValue() == null
                        || entry.getValue().isBlank()
                        || entry.getValue().length() > 128)) {
            throw new IllegalArgumentException(
                    "filter values must contain 1..128 characters"
            );
        }
        if (!SUPPORTED_FILTERS.containsAll(filters.keySet())) {
            throw new IllegalArgumentException(
                    "filters only support sourceType and language"
            );
        }
        filters = Map.copyOf(filters);
        Objects.requireNonNull(constraints, "constraints must not be null");
        validateFilters(constraints.relaxableFilters(), "relaxableFilters");
        validateFilters(constraints.narrowingFilters(), "narrowingFilters");
        if (!java.util.Collections.disjoint(
                        filters.keySet(),
                        constraints.relaxableFilters().keySet()
                )
                || !java.util.Collections.disjoint(
                        filters.keySet(),
                        constraints.narrowingFilters().keySet()
                )
                || !java.util.Collections.disjoint(
                        constraints.relaxableFilters().keySet(),
                        constraints.narrowingFilters().keySet()
                )) {
            throw new IllegalArgumentException(
                    "hard, relaxable and narrowing filter keys must not overlap"
            );
        }
        retrievalTarget = retrievalTarget == null ? "" : retrievalTarget.strip();
        if (retrievalTarget.length() > 4_000) {
            throw new IllegalArgumentException(
                    "retrievalTarget must not exceed 4000 characters"
            );
        }
        evidenceRequirements = List.copyOf(Objects.requireNonNull(
                evidenceRequirements,
                "evidenceRequirements must not be null"
        ));
        if (evidenceRequirements.size() > 32
                || evidenceRequirements.stream().anyMatch(Objects::isNull)
                || new java.util.HashSet<>(evidenceRequirements.stream()
                .map(EvidenceRequirement::id).toList()).size() != evidenceRequirements.size()) {
            throw new IllegalArgumentException(
                    "evidenceRequirements must contain at most 32 unique non-null ids"
            );
        }
        Objects.requireNonNull(
                configurationOverride,
                "configurationOverride must not be null"
        );
        Objects.requireNonNull(purpose, "purpose must not be null");
    }

    private static void validateFilters(Map<String, String> values, String fieldName) {
        if (values.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getValue() == null
                        || entry.getValue().isBlank()
                        || entry.getValue().length() > 128)) {
            throw new IllegalArgumentException(
                    fieldName + " values must contain 1..128 characters"
            );
        }
        if (!SUPPORTED_FILTERS.containsAll(values.keySet())) {
            throw new IllegalArgumentException(
                    fieldName + " only supports sourceType and language"
            );
        }
    }
}
