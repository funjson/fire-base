package dev.infinityknowledge.runtime.fusion;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用 Reciprocal Rank Fusion 合并不同量纲的召回结果。
 */
public final class ReciprocalRankFusion {
    private final int rankConstant;

    /**
     * 创建融合器。
     *
     * @param rankConstant RRF 平滑常数，通常取 60
     */
    public ReciprocalRankFusion(int rankConstant) {
        if (rankConstant < 1) {
            throw new IllegalArgumentException("rankConstant must be positive");
        }
        this.rankConstant = rankConstant;
    }

    /**
     * 按 Chunk 去重、累计排名贡献并归一化到零到一区间。
     *
     * @param candidates 各通道候选
     * @param limit 最大融合结果数
     * @return 有序融合候选
     */
    public List<FusedCandidate> fuse(List<RetrievalCandidate> candidates, int limit) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Map<UUID, MutableFusion> grouped = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : candidates) {
            MutableFusion fusion = grouped.computeIfAbsent(
                    candidate.chunkId(),
                    ignored -> new MutableFusion(candidate)
            );
            fusion.include(candidate, rankConstant);
        }
        double maximum = grouped.values().stream()
                .mapToDouble(MutableFusion::score)
                .max()
                .orElse(1.0D);
        return grouped.values().stream()
                .sorted(Comparator
                        .comparingDouble(MutableFusion::score)
                        .reversed()
                        .thenComparing(value -> value.representative().chunkId()))
                .limit(limit)
                .map(value -> value.toImmutable(maximum))
                .toList();
    }

    /**
     * 保存融合过程中的可变累计状态，作用域仅限单次方法调用。
     */
    private static final class MutableFusion {
        private RetrievalCandidate representative;
        private final EnumSet<RetrievalChannel> channels =
                EnumSet.noneOf(RetrievalChannel.class);
        private double score;

        /**
         * 使用第一个候选创建累计项。
         *
         * @param representative 初始代表候选
         */
        private MutableFusion(RetrievalCandidate representative) {
            this.representative = representative;
        }

        /**
         * 累计候选的排名贡献，并保留通道内评分更高的展示内容。
         *
         * @param candidate 新候选
         * @param rankConstant RRF 平滑常数
         */
        private void include(RetrievalCandidate candidate, int rankConstant) {
            ensureSameKnowledge(candidate);
            channels.add(candidate.channel());
            score += 1.0D / (rankConstant + candidate.rank());
            if (candidate.score() > representative.score()) {
                representative = candidate;
            }
        }

        /**
         * 防止同一 Chunk 标识被错误地跨租户或跨修订复用。
         *
         * @param candidate 待合并候选
         */
        private void ensureSameKnowledge(RetrievalCandidate candidate) {
            if (!representative.tenantId().equals(candidate.tenantId())
                    || !representative.spaceId().equals(candidate.spaceId())
                    || !representative.documentId().equals(candidate.documentId())
                    || !representative.revisionId().equals(candidate.revisionId())) {
                throw new IllegalStateException(
                        "retrieval candidate identity collision for chunk " + candidate.chunkId()
                );
            }
        }

        /**
         * 返回累计原始分数。
         *
         * @return RRF 原始分数
         */
        private double score() {
            return score;
        }

        /**
         * 返回当前代表候选。
         *
         * @return 代表候选
         */
        private RetrievalCandidate representative() {
            return representative;
        }

        /**
         * 转换为归一化不可变结果。
         *
         * @param maximum 当前批次最大原始分数
         * @return 融合候选
         */
        private FusedCandidate toImmutable(double maximum) {
            double rankStrength = maximum <= 0.0D ? 0.0D : score / maximum;
            double relevance = rankStrength * representative.score();
            return new FusedCandidate(representative, channels, relevance);
        }
    }
}
