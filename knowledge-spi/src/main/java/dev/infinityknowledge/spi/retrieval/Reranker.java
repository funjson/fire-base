package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;

import java.util.List;

/**
 * 定义对已授权融合候选进行精排的厂商无关端口。
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
}

