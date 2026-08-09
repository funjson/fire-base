package dev.infinityknowledge.controlplane.application;

/**
 * Indicates that a single-flight operation already owns the requested resource.
 */
public final class OperationInProgressException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public OperationInProgressException(String message) {
        super(message);
    }
}
