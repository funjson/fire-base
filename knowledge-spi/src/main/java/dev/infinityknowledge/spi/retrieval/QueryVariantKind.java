package dev.infinityknowledge.spi.retrieval;

/**
 * 标识模型生成查询的用途，便于 Trace 和后续评测区分召回贡献。
 */
public enum QueryVariantKind {
    /** 程序强制保留的原查询。 */
    ORIGINAL,
    /** 首轮可选的术语增强查询。 */
    TERM_EXPANSION,
    /** 针对 Coverage 缺口生成的补充查询。 */
    GAP_QUERY,
    /** 使用当前高排候选生成的伪相关反馈查询。 */
    PRF,
    /** 将问题提升到更一般层级的查询。 */
    STEP_BACK,
    /** 用于 Dense 召回的假设性答案文本。 */
    HYDE
}
