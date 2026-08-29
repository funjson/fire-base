package dev.infinityknowledge.domain.retrieval.observation;

/**
 * 检索观测事件所属的稳定执行阶段。
 *
 * <p>阶段名称同时是未来消息协议的判别字段；新增阶段只能追加，不能改变既有语义。</p>
 */
public enum RetrievalObservationStage {
    EXECUTION_STARTED,
    SPACE_ROUTING,
    CONFIGURATION_RESOLVED,
    QUERY_ANALYSIS,
    QUERY_PLANNING,
    RETRIEVAL_PLAN,
    RETRIEVAL_BRANCH,
    FUSION,
    RERANK,
    COVERAGE_CHECK,
    CHAIN_NODE_EVALUATED,
    CHAIN_NODE_COMPLETED,
    SPACE_CHANGED,
    EVIDENCE_BUILD,
    EXECUTION_TERMINAL,
    STAGE_FAILURE
}
