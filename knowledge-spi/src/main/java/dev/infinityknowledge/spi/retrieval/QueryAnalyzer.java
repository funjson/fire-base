package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;

/**
 * 把用户查询转换为受预算约束的检索计划。
 */
@FunctionalInterface
public interface QueryAnalyzer {

    /**
     * 分析查询意图并选择召回通道。
     *
     * @param query 原始知识查询
     * @return 检索计划
     */
    QueryPlan analyze(KnowledgeQuery query);
}

