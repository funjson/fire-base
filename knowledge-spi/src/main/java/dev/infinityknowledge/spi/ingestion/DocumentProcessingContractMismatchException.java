package dev.infinityknowledge.spi.ingestion;

/** 当前部署实际处理实现与 Space 或 Run 固化合同不一致。 */
public final class DocumentProcessingContractMismatchException
        extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** 对 HTTP、任务和运维告警统一暴露的稳定原因码。 */
    public static final String CODE = "PROCESSING_CONTRACT_MISMATCH";

    /** 创建不携带组件正文的安全漂移异常。 */
    public DocumentProcessingContractMismatchException() {
        super("current document processing implementation differs from frozen contract");
    }

    /** 保存根因类型供内部诊断，但对外仍只暴露稳定原因码。 */
    public DocumentProcessingContractMismatchException(Throwable cause) {
        super(
                "current document processing implementation differs from frozen contract",
                cause
        );
    }
}
