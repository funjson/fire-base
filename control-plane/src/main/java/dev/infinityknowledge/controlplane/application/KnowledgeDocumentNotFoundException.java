package dev.infinityknowledge.controlplane.application;

/** Indicates that a tenant-scoped document or authorized original source does not exist. */
public final class KnowledgeDocumentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates a non-sensitive not-found failure. */
    public KnowledgeDocumentNotFoundException() {
        super("knowledge document was not found");
    }

    /** Creates a non-sensitive contextual failure for internal diagnostics. */
    public KnowledgeDocumentNotFoundException(String message) {
        super(message);
    }
}
