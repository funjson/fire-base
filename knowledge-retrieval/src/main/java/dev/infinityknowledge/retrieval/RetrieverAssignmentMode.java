package dev.infinityknowledge.retrieval;

/**
 * 标识 Retrieval Planner 的整轮 Retriever 分配方式。
 *
 * <p>当前生产实现只装配 {@link #RULE_ONLY}。枚举先冻结业务语义，未来接入模型分类器时
 * 使用 {@link #RULE_WITH_LLM}，但模型仍不得覆盖 HyDE 仅向量等硬约束。</p>
 */
enum RetrieverAssignmentMode {
    /** 完全由稳定规则完成逐 Variant 分配。 */
    RULE_ONLY,
    /** 规则先限定合法模式，再由模型在合法方案中整轮选择。 */
    RULE_WITH_LLM
}
