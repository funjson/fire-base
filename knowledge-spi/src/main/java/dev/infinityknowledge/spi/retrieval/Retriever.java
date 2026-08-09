package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.util.List;

/**
 * 定义一个可独立观测和降级的知识召回通道。
 */
public interface Retriever {

    /**
     * 返回该实现对应的唯一召回通道。
     *
     * @return 召回通道
     */
    RetrievalChannel channel();

    /**
     * 在授权范围内召回有序候选。
     *
     * @param request 检索请求
     * @return 按相关性排序的候选
     */
    List<RetrievalCandidate> retrieve(RetrievalRequest request);
}

