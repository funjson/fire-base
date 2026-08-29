package dev.infinityknowledge.retrieval.fusion;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 保存同一 Chunk 在多个召回通道中的融合结果。
 *
 * @param representative 用于构建证据的代表候选
 * @param channels 命中通道
 * @param rrfScore RRF 原始分数，仅由各排名列表的名次和权重产生
 * @param normalizedRrfScore 当前融合批次内用于展示的零到一归一化分数
 * @param contributionsByBranch 各物理分支的 RRF 贡献
 * @param rrfRank 融合后的一基排名
 * @param rerankScore 可选的模型原始分数，只能在同一模型、同一查询、同一批次内比较
 * @param rerankReasonCode 精排器稳定原因码；未执行时为空
 */
public record FusedCandidate(
        RetrievalCandidate representative,
        Set<RetrievalChannel> channels,
        double rrfScore,
        double normalizedRrfScore,
        Map<String, Double> contributionsByBranch,
        int rrfRank,
        Double rerankScore,
        String rerankReasonCode
) {

    /**
     * 校验代表候选、分支贡献和阶段分数边界。
     */
    public FusedCandidate {
        Objects.requireNonNull(representative, "representative must not be null");
        channels = Set.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        if (!Double.isFinite(rrfScore) || rrfScore < 0.0D) {
            throw new IllegalArgumentException("rrfScore must be finite and non-negative");
        }
        if (!Double.isFinite(normalizedRrfScore)
                || normalizedRrfScore < 0.0D
                || normalizedRrfScore > 1.0D) {
            throw new IllegalArgumentException(
                    "normalizedRrfScore must be finite and between 0 and 1"
            );
        }
        contributionsByBranch = Map.copyOf(Objects.requireNonNull(
                contributionsByBranch,
                "contributionsByBranch must not be null"
        ));
        if (contributionsByBranch.isEmpty()
                || contributionsByBranch.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null
                        || !Double.isFinite(entry.getValue())
                        || entry.getValue() <= 0.0D)) {
            throw new IllegalArgumentException(
                    "contributionsByBranch must contain positive finite contributions"
            );
        }
        if (rrfRank < 1) {
            throw new IllegalArgumentException("rrfRank must be positive");
        }
        if (rerankScore != null && !Double.isFinite(rerankScore)) {
            throw new IllegalArgumentException("rerankScore must be finite when present");
        }
        rerankReasonCode = rerankReasonCode == null ? "" : rerankReasonCode;
        if (!rerankReasonCode.isEmpty()
                && !rerankReasonCode.matches("[A-Z0-9_]{1,32}")) {
            throw new IllegalArgumentException("rerankReasonCode must be a stable reason code");
        }
    }

    /**
     * Evidence 展示使用融合批次内的相对强度，绝不把模型原始分数当成全局相关度。
     *
     * @return 零到一展示分数
     */
    public double relevance() {
        return normalizedRrfScore;
    }

    /** 返回只改变精排附加信息、保持 RRF 事实不变的新结果。 */
    public FusedCandidate withRerank(Double score, String reasonCode) {
        return new FusedCandidate(
                representative,
                channels,
                rrfScore,
                normalizedRrfScore,
                contributionsByBranch,
                rrfRank,
                score,
                reasonCode
        );
    }
}
