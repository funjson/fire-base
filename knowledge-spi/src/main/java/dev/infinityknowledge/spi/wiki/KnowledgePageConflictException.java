package dev.infinityknowledge.spi.wiki;

/** Raised when optimistic locking or the page lifecycle rejects a write. */
public final class KnowledgePageConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public KnowledgePageConflictException(String message) {
        super(message);
    }
}
