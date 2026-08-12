package dev.infinityknowledge.spi.management;

/** Stable optimistic-lock or lifecycle-transition conflict. */
public final class DocumentLifecycleConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DocumentLifecycleConflictException(String message) {
        super(message);
    }
}
