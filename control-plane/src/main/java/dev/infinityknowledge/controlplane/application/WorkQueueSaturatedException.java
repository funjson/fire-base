package dev.infinityknowledge.controlplane.application;

/**
 * Indicates that a bounded background executor cannot accept more work.
 */
public final class WorkQueueSaturatedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public WorkQueueSaturatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
