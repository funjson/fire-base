package dev.infinityknowledge.spi.extraction;

/**
 * 多文件抽取任务的执行意图。
 *
 * <p>{@link #TEST_ONLY} 只生成诊断、预览和验收报告；{@link #INGEST} 允许后续运行时
 * 在抽取成功后进入正式写入链路。存储和页面共用该枚举，避免把试验模式硬编码进任务模型。</p>
 */
public enum ExtractionMode {
    /** 不写 Document、Revision 或索引的测试广场运行。 */
    TEST_ONLY,
    /** 抽取成功后可进入正式知识写入的生产运行。 */
    INGEST
}
