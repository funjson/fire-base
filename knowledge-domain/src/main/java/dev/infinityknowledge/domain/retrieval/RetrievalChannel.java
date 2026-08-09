package dev.infinityknowledge.domain.retrieval;

/**
 * 标识候选证据来自哪一种召回通道。
 */
public enum RetrievalChannel {
    /** Elasticsearch 或等价全文索引。 */
    KEYWORD,
    /** Milvus 或等价向量索引。 */
    VECTOR,
    /** Neo4j 关系遍历或图查询。 */
    GRAPH,
    /** 已发布知识页面。 */
    PAGE
}

