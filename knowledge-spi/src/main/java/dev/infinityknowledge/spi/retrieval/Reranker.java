package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;

import java.util.List;
import java.util.Objects;

/**
 * 定义对已授权且仍处于活动修订的召回候选进行精排的厂商无关端口。
 */
@FunctionalInterface
public interface Reranker {

    /**
     * 根据查询文本重排候选，不得加入输入列表之外的新内容。
     *
     * @param query 查询文本
     * @param candidates 已授权候选
     * @param limit 返回上限
     * @return 重排后的候选
     */
    List<RetrievalCandidate> rerank(
            String query,
            List<RetrievalCandidate> candidates,
            int limit
    );

    /**
     * 为未配置精排模型的部署提供保持原顺序的确定性实现。
     *
     * @return 不发起模型调用的 Reranker
     */
    static Reranker passthrough() {
        return (query, candidates, limit) -> {
            Objects.requireNonNull(query, "query must not be null");
            Objects.requireNonNull(candidates, "candidates must not be null");
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be positive");
            }
            return candidates.stream().limit(limit).toList();
        };
    }
}
