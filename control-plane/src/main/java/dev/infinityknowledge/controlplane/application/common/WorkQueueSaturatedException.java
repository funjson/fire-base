package dev.infinityknowledge.controlplane.application.common;

/**
 * 表示有界后台执行器已无法接收新的工作项。
 */
public final class WorkQueueSaturatedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public WorkQueueSaturatedException(String message) {
        super(message);
    }

    public WorkQueueSaturatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
