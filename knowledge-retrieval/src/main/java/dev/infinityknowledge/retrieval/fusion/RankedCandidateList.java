package dev.infinityknowledge.retrieval.fusion;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.util.List;
import java.util.Objects;

/**
 * 表示一条物理召回分支产生的独立排名列表。
 *
 * <p>RRF 的权重属于排名列表，而不是 Retriever 原始分数。即使两个分支使用同一通道，
 * 只要查询变体不同，也必须保留不同的 branchId 才能正确计算贡献和边际收益。</p>
 *
 * @param branchId 本次 Retrieval Plan 内稳定分支标识
 * @param channel 物理召回通道
 * @param weight RRF 列表权重
 * @param candidates 分支内一基排名候选
 */
public record RankedCandidateList(
        String branchId,
        RetrievalChannel channel,
        double weight,
        List<RetrievalCandidate> candidates
) {

    /** 校验分支身份、权重和通道归属。 */
    public RankedCandidateList {
        branchId = DomainChecks.requiredText(branchId, "branchId", 128);
        if (!branchId.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("branchId contains unsafe characters");
        }
        Objects.requireNonNull(channel, "channel must not be null");
        if (!Double.isFinite(weight) || weight <= 0.0D || weight > 100.0D) {
            throw new IllegalArgumentException("weight must be finite and in (0, 100]");
        }
        candidates = List.copyOf(Objects.requireNonNull(
                candidates,
                "candidates must not be null"
        ));
        if (candidates.stream().anyMatch(candidate ->
                candidate == null || candidate.channel() != channel)) {
            throw new IllegalArgumentException(
                    "candidates must be non-null and match the ranked-list channel"
            );
        }
    }
}
