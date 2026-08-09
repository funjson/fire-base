package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.spi.access.AccessScope;

import java.util.Objects;

/**
 * 表示传给单个 Retriever 的查询、计划和授权快照。
 *
 * @param query 原始知识查询
 * @param plan 检索计划
 * @param accessScope 授权过滤范围
 */
public record RetrievalRequest(
        KnowledgeQuery query,
        QueryPlan plan,
        AccessScope accessScope
) {

    /**
     * 校验请求租户与授权租户完全一致。
     */
    public RetrievalRequest {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(accessScope, "accessScope must not be null");
        if (!query.principal().tenantId().equals(accessScope.tenantId())) {
            throw new IllegalArgumentException("query tenant differs from access scope tenant");
        }
    }
}

