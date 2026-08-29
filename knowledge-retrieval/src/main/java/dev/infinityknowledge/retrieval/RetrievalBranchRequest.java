package dev.infinityknowledge.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.retrieval.query.PlannedQuery;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;

import java.util.Objects;

/**
 * 保存一个“查询变体 × Retriever”的物理召回分支。
 *
 * @param branchId 本次 Retrieval Plan 内稳定标识
 * @param queryVariant 查询变体及来源
 * @param request 仅包含一个物理通道的 Retriever 请求
 * @param rrfWeight 该分支在共同 RRF 中的权重
 */
record RetrievalBranchRequest(
        String branchId,
        PlannedQuery queryVariant,
        RetrievalRequest request,
        double rrfWeight
) {

    /** 校验分支身份、单通道约束和权重。 */
    RetrievalBranchRequest {
        branchId = DomainChecks.requiredText(branchId, "branchId", 128);
        Objects.requireNonNull(queryVariant, "queryVariant must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (request.plan().channels().size() != 1) {
            throw new IllegalArgumentException(
                    "retrieval branch request must contain exactly one channel"
            );
        }
        if (!Double.isFinite(rrfWeight) || rrfWeight <= 0.0D) {
            throw new IllegalArgumentException("rrfWeight must be finite and positive");
        }
    }
}
