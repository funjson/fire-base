package dev.infinityknowledge.domain.document;

/**
 * 定义解析后保留的文档结构元素。
 */
public enum ElementType {
    /** 文档或页面标题。 */
    TITLE,
    /** 有层级的章节标题。 */
    HEADING,
    /** 普通文本段落。 */
    PARAGRAPH,
    /** 保留行列语义的表格。 */
    TABLE,
    /** 有语言或类型信息的代码块。 */
    CODE,
    /** 有序或无序列表。 */
    LIST,
    /** 图片及其说明和 OCR 文本。 */
    IMAGE,
    /** 外部或内部附件。 */
    ATTACHMENT
}

