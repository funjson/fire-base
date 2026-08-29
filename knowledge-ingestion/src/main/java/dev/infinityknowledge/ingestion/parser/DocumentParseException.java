package dev.infinityknowledge.ingestion.parser;

/** 可稳定映射为摄取校验错误的文档解析异常。 */
public final class DocumentParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 创建不暴露来源正文的解析异常。 */
    public DocumentParseException(String message) {
        super(message);
    }

    /** 创建携带技术原因但不包含来源正文的解析异常。 */
    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
