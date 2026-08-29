package dev.infinityknowledge.controlplane.application.document;

/** 表示租户范围内的文档或已授权原件不存在。 */
public final class KnowledgeDocumentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 创建不含敏感信息的未找到异常。 */
    public KnowledgeDocumentNotFoundException() {
        super("knowledge document was not found");
    }

    /** 创建供内部诊断使用且不包含敏感信息的上下文异常。 */
    public KnowledgeDocumentNotFoundException(String message) {
        super(message);
    }
}
