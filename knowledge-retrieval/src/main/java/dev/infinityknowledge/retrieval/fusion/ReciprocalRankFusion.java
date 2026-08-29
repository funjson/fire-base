package dev.infinityknowledge.retrieval.fusion;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
     * 使用共同的 {@code rankConstant} 和分支权重融合独立排名列表。
     *
     * <p>Retriever 原始分数仅用于选择引用展示代表，不参与 RRF 评分。每个分支内同一
     * Chunk 只采用最佳名次，避免重复返回导致贡献被错误放大。</p>
     *
     * @param rankedLists 物理召回分支结果
     * @param limit 最大融合结果数
     * @return 带分支贡献和稳定排名的融合候选
     */
    public List<FusedCandidate> fuseRankedLists(
            List<RankedCandidateList> rankedLists,
            int limit
    ) {
        Objects.requireNonNull(rankedLists, "rankedLists must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Map<UUID, MutableFusion> grouped = new LinkedHashMap<>();
        Set<String> branchIds = new HashSet<>();
        for (RankedCandidateList list : rankedLists) {
            Objects.requireNonNull(list, "rankedLists must not contain null values");
            if (!branchIds.add(list.branchId())) {
                throw new IllegalArgumentException("rankedLists must use unique branch ids");
            }
            Map<UUID, RetrievalCandidate> bestByChunk = new LinkedHashMap<>();
            for (RetrievalCandidate candidate : list.candidates()) {
                bestByChunk.merge(
                        candidate.chunkId(),
                        candidate,
                        (left, right) -> left.rank() <= right.rank() ? left : right
                );
            }
            for (RetrievalCandidate candidate : bestByChunk.values()) {
                MutableFusion fusion = grouped.computeIfAbsent(
                        candidate.chunkId(),
                        ignored -> new MutableFusion(candidate)
                );
                fusion.include(candidate, list.branchId(), list.weight(), rankConstant);
            }
        }
        double maximum = grouped.values().stream()
                .mapToDouble(MutableFusion::score)
                .max()
                .orElse(1.0D);
        List<MutableFusion> ordered = grouped.values().stream()
                .sorted(Comparator
                        .comparingDouble(MutableFusion::score)
                        .reversed()
                        .thenComparing(value -> value.representative().chunkId()))
                .limit(limit)
                .toList();
        java.util.concurrent.atomic.AtomicInteger rank = new java.util.concurrent.atomic.AtomicInteger();
        return ordered.stream()
                .map(value -> value.toImmutable(maximum, rank.incrementAndGet()))
                .toList();
    }

    /**
     * 保存融合过程中的可变累计状态，作用域仅限单次方法调用。
     */
    private static final class MutableFusion {
        private RetrievalCandidate representative;
        private final EnumSet<RetrievalChannel> channels =
                EnumSet.noneOf(RetrievalChannel.class);
        private final Map<String, Double> contributions = new LinkedHashMap<>();
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
         * @param branchId 物理分支标识
         * @param weight 分支权重
         * @param rankConstant RRF 平滑常数
         */
        private void include(
                RetrievalCandidate candidate,
                String branchId,
                double weight,
                int rankConstant
        ) {
            ensureSameKnowledge(candidate);
            channels.add(candidate.channel());
            double contribution = weight / (rankConstant + candidate.rank());
            contributions.put(branchId, contribution);
            score += contribution;
            if (shouldReplaceRepresentative(candidate)) {
                representative = candidate;
            }
        }

        /**
         * 为最终 Citation 选择展示代表。
         *
         * <p>同一个 Chunk 在不同索引中的正文应当一致，但图检索等通道可能尚未返回
         * SourceSpan。只按通道分数替换代表会把可高亮的定位信息丢失，因此先优先保留
         * 非空范围；定位能力相同时再选择分数较高的候选。</p>
         */
        private boolean shouldReplaceRepresentative(RetrievalCandidate candidate) {
            boolean candidateHasSourceSpans = !candidate.sourceSpans().isEmpty();
            boolean representativeHasSourceSpans = !representative.sourceSpans().isEmpty();
            if (candidateHasSourceSpans != representativeHasSourceSpans) {
                return candidateHasSourceSpans;
            }
            return candidate.score() > representative.score();
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
         * @param rank 融合后排名
         * @return 融合候选
         */
        private FusedCandidate toImmutable(double maximum, int rank) {
            double rankStrength = maximum <= 0.0D ? 0.0D : score / maximum;
            return new FusedCandidate(
                    representative,
                    channels,
                    score,
                    rankStrength,
                    contributions,
                    rank,
                    null,
                    ""
            );
        }
    }
}
