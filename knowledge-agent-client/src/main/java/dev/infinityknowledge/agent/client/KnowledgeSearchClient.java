package dev.infinityknowledge.agent.client;

import java.util.UUID;

/** 定义 Agent 使用的版本化企业知识检索客户端。 */
@FunctionalInterface
public interface KnowledgeSearchClient {

    /**
     * 在访问令牌所代表主体的权限范围内检索知识证据。
     *
     * @param requestId Agent 生成并用于端到端关联的请求标识
     * @param request 检索请求
     * @return 可直接作为 Agent 工具观察结果的证据响应
     */
    KnowledgeSearchResponse search(UUID requestId, KnowledgeSearchRequest request);
}
