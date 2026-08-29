package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 保存术语服务完成消歧、等价关系筛选和数量截断后的单个扩展结果。
 *
 * <p>所有命中合并成一条 Q1；{@code appliedTerms} 用于 Runtime 校验外部实现确实
 * 遵守 Space 的扩展词上限，不用于日志或 Trace 展示原文。</p>
 *
 * @param expandedQuery 保留 Q0 检索目标后的自然可检索文本
 * @param appliedTerms 实际加入 Q1 的有限等价术语
 */
public record TerminologyExpansion(
        String expandedQuery,
        List<String> appliedTerms
) {

    /** 拒绝空结果、重复术语和无界外部返回。 */
    public TerminologyExpansion {
        expandedQuery = DomainChecks.requiredText(
                expandedQuery,
                "expandedQuery",
                16_000
        );
        appliedTerms = List.copyOf(Objects.requireNonNull(
                appliedTerms,
                "appliedTerms must not be null"
        ));
        if (appliedTerms.isEmpty() || appliedTerms.size() > 64) {
            throw new IllegalArgumentException(
                    "appliedTerms must contain between 1 and 64 terms"
            );
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String term : appliedTerms) {
            String value = DomainChecks.requiredText(term, "applied term", 256);
            if (!normalized.add(value)) {
                throw new IllegalArgumentException("appliedTerms must be unique");
            }
        }
        appliedTerms = List.copyOf(normalized);
    }
}
