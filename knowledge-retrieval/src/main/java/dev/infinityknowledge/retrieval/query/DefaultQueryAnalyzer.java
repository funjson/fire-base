package dev.infinityknowledge.retrieval.query;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.spi.retrieval.QueryAnalyzer;
import dev.infinityknowledge.spi.retrieval.QueryPlan;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 提供无需模型即可运行的确定性查询分析基线。
 *
 * <p>该实现始终启用关键词和向量召回；当查询包含关系型表达时增加图检索。
 * 后续 LLM Analyzer 可以替换该实现，但必须保持相同预算和可审计输出。</p>
 */
public final class DefaultQueryAnalyzer implements QueryAnalyzer {
    private static final Set<String> GRAPH_MARKERS = Set.of(
            "依赖", "关系", "调用", "影响", "关联", "上游", "下游",
            "depends", "calls", "related", "impact", "upstream", "downstream"
    );
    private final int candidateMultiplier;
    private final Set<RetrievalChannel> availableChannels;

    /**
     * 创建候选预算受控的查询分析器。
     *
     * @param candidateMultiplier 每个通道相对最终 topK 的候选倍数
     */
    public DefaultQueryAnalyzer(int candidateMultiplier) {
        this(candidateMultiplier, EnumSet.allOf(RetrievalChannel.class));
    }

    /**
     * 创建仅规划已安装通道的查询分析器。
     *
     * @param candidateMultiplier 每个通道相对最终 topK 的候选倍数
     * @param availableChannels 运行时已注册的召回通道
     */
    public DefaultQueryAnalyzer(
            int candidateMultiplier,
            Set<RetrievalChannel> availableChannels
    ) {
        if (candidateMultiplier < 1 || candidateMultiplier > 20) {
            throw new IllegalArgumentException("candidateMultiplier must be between 1 and 20");
        }
        Objects.requireNonNull(availableChannels, "availableChannels must not be null");
        if (availableChannels.isEmpty()) {
            throw new IllegalArgumentException("at least one retrieval channel is required");
        }
        this.candidateMultiplier = candidateMultiplier;
        this.availableChannels = Set.copyOf(availableChannels);
    }

    /**
     * 规范化空白并根据稳定关键词选择图召回。
     *
     * @param query 原始知识查询
     * @return 确定性检索计划
     */
    @Override
    public QueryPlan analyze(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String normalized = query.text().replaceAll("\\s+", " ").strip();
        String lowered = normalized.toLowerCase(Locale.ROOT);
        EnumSet<RetrievalChannel> channels = EnumSet.noneOf(RetrievalChannel.class);
        if (availableChannels.contains(RetrievalChannel.KEYWORD)) {
            channels.add(RetrievalChannel.KEYWORD);
        }
        if (availableChannels.contains(RetrievalChannel.VECTOR)) {
            channels.add(RetrievalChannel.VECTOR);
        }
        if (availableChannels.contains(RetrievalChannel.GRAPH)
                && GRAPH_MARKERS.stream().anyMatch(lowered::contains)) {
            channels.add(RetrievalChannel.GRAPH);
        }
        if (availableChannels.contains(RetrievalChannel.PAGE)) {
            channels.add(RetrievalChannel.PAGE);
        }
        int candidateLimit = Math.min(1_000, query.topK() * candidateMultiplier);
        return new QueryPlan(query.text(), normalized, channels, candidateLimit);
    }
}
