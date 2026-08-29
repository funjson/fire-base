package dev.infinityknowledge.spi.extraction;

/** 同一租户和空间已经存在活动抽取试验任务。 */
public final class ActiveExtractionRunException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 创建不携带数据库细节的稳定冲突异常。 */
    public ActiveExtractionRunException() {
        super("an extraction run is already active for this space");
    }
}
