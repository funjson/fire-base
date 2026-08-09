package dev.infinityknowledge.domain.document;

/**
 * 区分知识内容的权威来源类型。
 */
public enum SourceType {
    /** 通过上传 API 接收的文件。 */
    UPLOAD,
    /** Obsidian Vault 中的 Markdown 或附件。 */
    OBSIDIAN,
    /** 由通用文件系统连接器读取的内容。 */
    FILESYSTEM,
    /** 由外部 HTTP API 同步的内容。 */
    API,
    /** 由代码仓库同步的内容。 */
    GIT,
    /** 系统编译生成且必须保留来源的知识页面。 */
    COMPILED
}

