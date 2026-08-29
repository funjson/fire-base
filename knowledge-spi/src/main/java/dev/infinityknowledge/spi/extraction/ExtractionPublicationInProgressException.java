package dev.infinityknowledge.spi.extraction;

/** 正式发布已进入不可安全取消的数据库窗口。 */
public final class ExtractionPublicationInProgressException extends RuntimeException {

    public static final String CODE = "EXTRACTION_PUBLICATION_IN_PROGRESS";
    private static final long serialVersionUID = 1L;

    /** 创建不携带正文、数据库语句或内部异常的稳定冲突。 */
    public ExtractionPublicationInProgressException() {
        super("the extraction item is publishing and cannot be cancelled");
    }

    /** 返回可直接映射到 API 的稳定错误码。 */
    public String code() {
        return CODE;
    }
}
