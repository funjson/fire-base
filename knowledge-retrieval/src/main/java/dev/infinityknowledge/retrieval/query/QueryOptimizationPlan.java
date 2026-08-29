package dev.infinityknowledge.retrieval.query;

import dev.infinityknowledge.spi.retrieval.QueryVariantKind;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 表示只包含约束解析结果和查询变体的查询优化计划。
 *
 * <p>每轮所有 Variant 共享同一份已解析约束；约束发生变化时创建下一版同类型计划，
 * 不引入额外 Corrective Plan。Retriever 分配和执行状态不得进入本对象。</p>
 *
 * @param kind 本计划是首轮、局部查询反馈还是约束变更轮
 * @param resolvedConstraints 本轮所有查询变体共享的已解析约束
 * @param variants 本轮真正要执行的有序变体
 */
public record QueryOptimizationPlan(
        Kind kind,
        ResolvedConstraints resolvedConstraints,
        List<PlannedQuery> variants
) {

    /**
     * 保证首轮以 Q0 开始，反馈轮只执行当前 Chain 产生的局部变体。
     *
     * <p>Q0 仍保存在请求运行状态中，但同一 Space、相同约束与配置下的
     * 反馈轮不应重复发起 Q0 物理召回。</p>
     */
    public QueryOptimizationPlan {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(
                resolvedConstraints,
                "resolvedConstraints must not be null"
        );
        variants = List.copyOf(Objects.requireNonNull(variants, "variants must not be null"));
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("query optimization plan must not be empty");
        }
        long originalCount = variants.stream()
                .filter(value -> value.kind() == QueryVariantKind.ORIGINAL)
                .count();
        if ((kind == Kind.FIRST_ROUND || kind == Kind.CONSTRAINT_CHANGE)
                && (variants.getFirst().kind() != QueryVariantKind.ORIGINAL
                        || originalCount != 1L)) {
            throw new IllegalArgumentException(
                    "first-round and constraint-change plans require one leading Q0"
            );
        }
        if (kind == Kind.QUERY_FEEDBACK
                && (variants.size() != 1 || originalCount != 0L)) {
            throw new IllegalArgumentException(
                    "feedback query optimization plan must contain one non-original variant"
            );
        }
        if (new HashSet<>(variants.stream().map(PlannedQuery::id).toList()).size()
                != variants.size()) {
            throw new IllegalArgumentException("planned query ids must be unique");
        }
    }

    /** 创建必须包含 Q0 的首轮计划。 */
    public static QueryOptimizationPlan firstRound(
            ResolvedConstraints constraints,
            List<PlannedQuery> variants
    ) {
        return new QueryOptimizationPlan(Kind.FIRST_ROUND, constraints, variants);
    }

    /** 创建只执行当前局部变体的反馈轮计划。 */
    public static QueryOptimizationPlan feedback(
            ResolvedConstraints constraints,
            PlannedQuery variant
    ) {
        return new QueryOptimizationPlan(Kind.QUERY_FEEDBACK, constraints, List.of(variant));
    }

    /** 约束变化后创建重新执行 Q0 的下一轮计划。 */
    public static QueryOptimizationPlan constraintChange(
            ResolvedConstraints constraints,
            String originalQuery
    ) {
        return new QueryOptimizationPlan(
                Kind.CONSTRAINT_CHANGE,
                constraints,
                List.of(PlannedQuery.original(originalQuery))
        );
    }

    /** 计划阶段；阶段决定 Q0 在本轮是否真正执行。 */
    public enum Kind {
        FIRST_ROUND,
        QUERY_FEEDBACK,
        CONSTRAINT_CHANGE
    }
}
