package dev.infinityknowledge.ingestion;

/**
 * 表示单个来源或一次来源批次超过服务端配置的字节预算。
 *
 * <p>该异常不携带文件正文、文件名或声明大小，HTTP 边界可安全地将它映射为 413。</p>
 */
public final class SourceSizeLimitExceededException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** 创建不暴露来源细节的大小门禁异常。 */
    public SourceSizeLimitExceededException() {
        super("source exceeds configured size limit");
    }

    /** 创建由算术溢出触发的大小门禁异常。 */
    public SourceSizeLimitExceededException(Throwable cause) {
        super("source exceeds configured size limit", cause);
    }
}
