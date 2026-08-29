package dev.infinityknowledge.domain.retrieval;

/** 解释检索终态的稳定停止原因。 */
public enum RetrievalStopReason {
    /** 已达到 Coverage 阈值。 */
    SUFFICIENCY_THRESHOLD_REACHED,
    /** Space 配置显式关闭 Coverage。 */
    COVERAGE_DISABLED,
    /** 没有可执行的 Evidence Requirement。 */
    EVIDENCE_REQUIREMENTS_MISSING,
    /** Coverage Judge 在技术重试后失败。 */
    COVERAGE_CHECK_FAILED,
    /** 唯一检索尝试预算已耗尽。 */
    RETRIEVAL_BUDGET_EXHAUSTED,
    /** 固定 Chain 已经执行完毕。 */
    OPTIMIZATION_CHAIN_EXHAUSTED,
    /** 剩余 Chain 节点均不满足系统前置条件。 */
    NO_APPLICABLE_OPTIMIZATION_NODE,
    /** 关键检索阶段发生技术失败。 */
    TECHNICAL_FAILURE
}
