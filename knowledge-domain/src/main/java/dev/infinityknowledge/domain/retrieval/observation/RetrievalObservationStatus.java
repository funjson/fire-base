package dev.infinityknowledge.domain.retrieval.observation;

/**
 * 描述一个检索阶段或整次执行的技术状态，不承载动态异常文本。
 */
public enum RetrievalObservationStatus {
    STARTED(false),
    SUCCEEDED(true),
    DEGRADED(true),
    SKIPPED(false),
    FAILED(true),
    TIMED_OUT(true),
    CANCELLED(true),
    REJECTED(true),
    NOT_CONFIGURED(false);

    private final boolean terminalAllowed;

    RetrievalObservationStatus(boolean terminalAllowed) {
        this.terminalAllowed = terminalAllowed;
    }

    /**
     * 返回该状态能否作为整次执行的终态。
     */
    public boolean terminalAllowed() {
        return terminalAllowed;
    }
}
