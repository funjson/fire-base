package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.List;
import java.util.Objects;

/**
 * 固定 Optimization Chain 中查询生成节点的有界输入。
 *
 * <p>请求不包含租户、授权、Space 选择、阈值或剩余预算。模型只生成指定策略的一条
 * 检索表示，不能选择下一个策略。</p>
 */
public record FeedbackQueryPlanningRequest(
        QueryVariantKind strategy,
        String originalQuery,
        String retrievalTarget,
        List<String> missingGaps,
        List<CoverageCandidate> evidenceMemory
) {
    /** 校验固定策略类型和模型输入预算。 */
    public FeedbackQueryPlanningRequest {
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (strategy == QueryVariantKind.ORIGINAL
                || strategy == QueryVariantKind.TERM_EXPANSION) {
            throw new IllegalArgumentException(
                    "feedback strategy must be GAP_QUERY, PRF, STEP_BACK or HYDE"
            );
        }
        originalQuery = DomainChecks.requiredText(originalQuery, "originalQuery", 16_000);
        retrievalTarget = retrievalTarget == null ? "" : retrievalTarget.strip();
        if (retrievalTarget.length() > 4_000) {
            throw new IllegalArgumentException("retrievalTarget must not exceed 4000 characters");
        }
        missingGaps = List.copyOf(Objects.requireNonNull(
                missingGaps,
                "missingGaps must not be null"
        ));
        if (missingGaps.size() > 32 || missingGaps.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 2_000)) {
            throw new IllegalArgumentException("missingGaps contains invalid values");
        }
        evidenceMemory = List.copyOf(Objects.requireNonNull(
                evidenceMemory,
                "evidenceMemory must not be null"
        ));
        if (evidenceMemory.size() > 100 || evidenceMemory.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("evidenceMemory must not exceed 100 candidates");
        }
    }
}
