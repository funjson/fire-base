package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.util.Objects;
import java.util.Set;

/**
 * 表示 Query Analyzer 生成的确定性检索计划。
 *
 * @param originalQuery 原查询
 * @param normalizedQuery 规范化查询
 * @param channels 需要执行的召回通道
 * @param candidateLimit 每个通道最大候选数
 */
public record QueryPlan(
        String originalQuery,
        String normalizedQuery,
        Set<RetrievalChannel> channels,
        int candidateLimit
) {

    /**
     * 校验计划至少包含一个通道和有效候选预算。
     */
    public QueryPlan {
        originalQuery = DomainChecks.requiredText(originalQuery, "originalQuery", 16_000);
        normalizedQuery = DomainChecks.requiredText(normalizedQuery, "normalizedQuery", 16_000);
        channels = Set.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("query plan must contain at least one channel");
        }
        if (candidateLimit < 1 || candidateLimit > 1_000) {
            throw new IllegalArgumentException("candidateLimit must be between 1 and 1000");
        }
    }
}

