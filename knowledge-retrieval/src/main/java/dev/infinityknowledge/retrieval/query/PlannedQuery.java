package dev.infinityknowledge.retrieval.query;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.spi.retrieval.QueryVariantKind;

import java.util.Objects;

/**
 * 保存一次 Query Optimization Plan 中不会丢失来源信息的查询变体。
 *
 * @param id 单次执行内稳定标识
 * @param kind 变体来源
 * @param text 提交给 Retriever 的查询文本
 * @param provider 生成 Provider；原查询为 deterministic
 * @param model 实际模型；原查询为 none
 */
public record PlannedQuery(
        String id,
        QueryVariantKind kind,
        String text,
        String provider,
        String model
) {

    /** 校验查询变体和可观测来源字段。 */
    public PlannedQuery {
        id = DomainChecks.requiredText(id, "planned query id", 64);
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("planned query id contains unsafe characters");
        }
        Objects.requireNonNull(kind, "kind must not be null");
        text = DomainChecks.requiredText(text, "planned query text", 16_000);
        provider = DomainChecks.requiredText(provider, "provider", 64);
        model = DomainChecks.requiredText(model, "model", 128);
    }

    /** 创建始终位于计划首位的原查询。 */
    public static PlannedQuery original(String text) {
        return new PlannedQuery(
                "q0",
                QueryVariantKind.ORIGINAL,
                text,
                "deterministic",
                "none"
        );
    }
}
