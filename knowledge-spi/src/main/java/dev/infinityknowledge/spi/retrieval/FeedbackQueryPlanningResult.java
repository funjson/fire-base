package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.Objects;

/** 固定 Chain 节点生成的一条查询变体及其模型版本。 */
public record FeedbackQueryPlanningResult(
        QueryVariant variant,
        String provider,
        String model
) {
    /** 确保模型只能返回调用方指定后再由执行层复核的有界变体。 */
    public FeedbackQueryPlanningResult {
        Objects.requireNonNull(variant, "variant must not be null");
        provider = DomainChecks.requiredText(provider, "provider", 64);
        model = DomainChecks.requiredText(model, "model", 128);
    }
}
