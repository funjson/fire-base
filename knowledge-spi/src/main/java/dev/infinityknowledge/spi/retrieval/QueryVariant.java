package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.Objects;

/**
 * 表示 Query Planner 生成的一条受预算约束的查询变体。
 *
 * @param id 单次计划内稳定且不含查询正文的标识
 * @param kind 变体用途
 * @param text 可直接提交给 Retriever 的独立查询文本
 */
public record QueryVariant(String id, QueryVariantKind kind, String text) {

    /**
     * 校验标识、类型和查询文本，防止 Provider 返回无界内容。
     */
    public QueryVariant {
        id = DomainChecks.requiredText(id, "query variant id", 64);
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("query variant id contains unsafe characters");
        }
        Objects.requireNonNull(kind, "kind must not be null");
        text = DomainChecks.requiredText(text, "query variant text", 16_000);
    }
}
