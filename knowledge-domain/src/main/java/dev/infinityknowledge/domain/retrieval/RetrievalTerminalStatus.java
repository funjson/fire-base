package dev.infinityknowledge.domain.retrieval;

/**
 * 描述一次逻辑检索最终如何结束，与阶段技术状态和是否发生降级分开表达。
 */
public enum RetrievalTerminalStatus {
    /** Coverage 已完成且达到当前 Space 阈值。 */
    SUFFICIENT,
    /** Coverage 已完成，但停止时仍未达到阈值。 */
    INSUFFICIENT,
    /** Space 显式关闭 Coverage，按配置正常返回。 */
    NOT_EVALUATED,
    /** 调用方未给要求且兜底也无法得到要求。 */
    EVIDENCE_REQUIREMENTS_MISSING,
    /** Coverage Judge 技术重试后仍无法得到可信结论。 */
    CHECK_FAILED,
    /** 关键系统错误导致未能完成有效检索。 */
    TECHNICAL_FAILED
}
