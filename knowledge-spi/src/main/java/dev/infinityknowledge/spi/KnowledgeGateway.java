package dev.infinityknowledge.spi;

import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;

/**
 * 定义 Agent 和应用访问知识运行时的稳定入口。
 */
@FunctionalInterface
public interface KnowledgeGateway {

    /**
     * 在调用主体权限范围内检索并构建证据包。
     *
     * @param query 已绑定认证主体的知识查询
     * @return 可追溯证据包
     */
    EvidenceBundle retrieve(KnowledgeQuery query);
}

