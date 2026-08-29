package dev.infinityknowledge.ingestion.cleaning;

import dev.infinityknowledge.ingestion.parser.ParsedDocument;

/**
 * 在 Parser 与 Chunker 之间应用确定性的文档内容治理规则。
 *
 * <p>实现不得改写正文、来源定位或元素身份；为避免已排除标题污染
 * 检索上下文或产生悬空父子关系，可确定性重建 sectionPath 与 parentId。
 * 模型清洗、任意正则和脱敏等具有独立风险边界的能力不属于该契约。</p>
 */
public interface DocumentCleaningPolicy {

    /** 持久化到仅元数据 Element 上的稳定清洗去向属性键。 */
    String CLEANING_DISPOSITION_ATTRIBUTE = "cleaningDisposition";

    /** 持久化到仅元数据 Element 上的稳定清洗原因码属性键。 */
    String CLEANING_REASON_CODE_ATTRIBUTE = "cleaningReasonCode";

    /**
     * 返回仅由规则版本和有效配置决定的稳定处理契约。
     *
     * <p>契约必须能在 Parse 前计算，以便调用方把它纳入修订处理指纹；单次文档
     * 的规则命中数不得参与契约。</p>
     *
     * @param configuration 本次清洗使用的有效配置
     * @return 可进入处理指纹的稳定契约
     */
    String contract(DocumentCleaningConfiguration configuration);

    /**
     * 清洗一份结构化 Parser 输出。
     *
     * @param document 按来源阅读顺序排列的 Parser 输出
     * @param configuration 本次清洗使用的有效配置
     * @return 分离可索引元素、仅元数据元素和审计计数的结果
     */
    DocumentCleaningResult clean(
            ParsedDocument document,
            DocumentCleaningConfiguration configuration
    );
}
