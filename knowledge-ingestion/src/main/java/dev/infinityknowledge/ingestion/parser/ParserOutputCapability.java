package dev.infinityknowledge.ingestion.parser;

/**
 * 描述 Parser Adapter 对每次成功解析都能保证提供的输出能力。
 *
 * <p>这些值用于配置兼容性判断和处理指纹，不是质量评分。Adapter 只有在所有受支持
 * 格式和预设下都能满足语义时才可声明，避免控制面允许实际无法执行的组合。</p>
 */
public enum ParserOutputCapability {
    /** 输出统一的 {@link ParsedDocument} 与有序结构元素。 */
    STANDARD_ELEMENTS,
    /** 保留标题层级、父子关系或可供 Chunker 使用的章节路径。 */
    HIERARCHY,
    /** 为正文元素提供可靠的一基页码来源信息。 */
    PAGE_NUMBER,
    /** 为来源元素提供可回溯的页面坐标框。 */
    BOUNDING_BOX,
    /** 识别表格边界，但只保证输出供检索使用的扁平文本，不保留行列模型。 */
    FLAT_TABLE_TEXT,
    /** 保留表格的行列边界，而不是仅返回无结构连续文本。 */
    TABLE_STRUCTURE,
    /** 同时保留可供同厂商后续 Adapter 使用的原生解析产物。 */
    NATIVE_ARTIFACT
}
