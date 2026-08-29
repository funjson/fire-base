package dev.infinityknowledge.controlplane.application.retrieval;

/** 当前租户内不存在指定 request 的检索观测报告。 */
public final class RetrievalObservationReportNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 创建不携带租户、请求或数据库细节的缺失异常。 */
    public RetrievalObservationReportNotFoundException() {
        super("retrieval observation report not found");
    }
}
