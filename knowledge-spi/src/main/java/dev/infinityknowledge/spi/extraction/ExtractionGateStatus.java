package dev.infinityknowledge.spi.extraction;

/**
 * 抽取验收门禁状态，与任务执行状态相互独立。
 *
 * <p>任务 {@code SUCCEEDED} 只表示 Parse/Clean/Chunk 完成；只有真实 Dataset Runner
 * 返回通过时才是 {@link #PASSED}，不得根据聚合诊断推测门禁结果。</p>
 */
public enum ExtractionGateStatus {
    /** 未选择数据集、任务尚未完成，或尚未执行真实门禁。 */
    NOT_EVALUATED,
    /** 所有数据集 Case 的硬门禁均通过。 */
    PASSED,
    /** Runner 正常完成，但至少一个硬门禁失败。 */
    FAILED,
    /** Dataset、Observation 或 Runner 执行失败，无法形成业务判定。 */
    ERROR
}
