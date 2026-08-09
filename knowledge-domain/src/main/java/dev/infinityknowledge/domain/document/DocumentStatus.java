package dev.infinityknowledge.domain.document;

/**
 * 定义知识文档的治理生命周期。
 */
public enum DocumentStatus {
    /** 尚未允许生产检索的草稿。 */
    DRAFT,
    /** 已发布并可被授权用户检索。 */
    ACTIVE,
    /** 已被新版本替代，默认不参与当前检索。 */
    DEPRECATED,
    /** 仅供审计保留的归档文档。 */
    ARCHIVED,
    /** 已发布删除墓碑并等待外部索引清理。 */
    DELETED
}

