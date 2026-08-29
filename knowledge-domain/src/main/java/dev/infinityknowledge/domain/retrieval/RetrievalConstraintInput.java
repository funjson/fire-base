package dev.infinityknowledge.domain.retrieval;

import java.util.Map;
import java.util.Objects;

/**
 * 表示 Agent 显式交给检索运行时、但不属于不可变硬过滤的逻辑约束。
 *
 * <p>{@code relaxableFilters} 首轮即生效，但调用方明确允许后续 RELAX 节点移除；
 * {@code narrowingFilters} 首轮不生效，只能由 NARROW 节点在证据过宽时加入。
 * 普通 {@link KnowledgeQuery#filters()} 始终是不可放宽的硬约束。</p>
 *
 * @param relaxableFilters 首轮生效且允许确定性放宽的过滤条件
 * @param narrowingFilters Agent 已提供、允许后续确定性收窄的候选过滤条件
 */
public record RetrievalConstraintInput(
        Map<String, String> relaxableFilters,
        Map<String, String> narrowingFilters
) {

    /** 返回不允许改变任何约束的默认输入。 */
    public static RetrievalConstraintInput empty() {
        return new RetrievalConstraintInput(Map.of(), Map.of());
    }

    /** 防止调用期间修改约束集合。 */
    public RetrievalConstraintInput {
        relaxableFilters = Map.copyOf(Objects.requireNonNull(
                relaxableFilters,
                "relaxableFilters must not be null"
        ));
        narrowingFilters = Map.copyOf(Objects.requireNonNull(
                narrowingFilters,
                "narrowingFilters must not be null"
        ));
    }
}
