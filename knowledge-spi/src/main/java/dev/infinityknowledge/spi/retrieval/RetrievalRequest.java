package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.spi.access.AccessScope;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示传给单个 Retriever 的查询、计划和授权快照。
 *
 * @param query 原始知识查询
 * @param plan 检索计划
 * @param accessScope 授权过滤范围
 * @param deadline 本次检索的绝对截止时间
 */
public record RetrievalRequest(
        KnowledgeQuery query,
        QueryPlan plan,
        AccessScope accessScope,
        Instant deadline
) {

    /**
     * 为兼容既有调用方创建不强制截止时间的请求。
     *
     * @param query 原始查询
     * @param plan 检索计划
     * @param accessScope 授权范围
     */
    public RetrievalRequest(
            KnowledgeQuery query,
            QueryPlan plan,
            AccessScope accessScope
    ) {
        this(query, plan, accessScope, Instant.MAX);
    }

    /**
     * 校验请求租户与授权租户完全一致。
     */
    public RetrievalRequest {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(accessScope, "accessScope must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        if (!query.principal().tenantId().equals(accessScope.tenantId())) {
            throw new IllegalArgumentException("query tenant differs from access scope tenant");
        }
    }
}
