package dev.infinityknowledge.domain.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

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
 * @param filters 业务元数据过滤条件
 */
public record KnowledgeQuery(
        UUID requestId,
        PrincipalContext principal,
        String text,
        Set<KnowledgeSpaceId> spaceIds,
        int topK,
        Map<String, String> filters
) {
    private static final Set<String> SUPPORTED_FILTERS = Set.of(
            "language",
            "sourceType"
    );

    /**
     * 校验查询文本、返回数量和调用期间不可变的过滤条件。
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
    }
}
