package dev.infinityknowledge.controlplane.application.ingestion.extraction;

/** 当前租户和空间中不存在指定抽取试验任务或 Item。 */
public final class ExtractionRunNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 创建不暴露其他租户资源是否存在的缺失异常。 */
    public ExtractionRunNotFoundException() {
        super("extraction run or item does not exist");
    }
}
