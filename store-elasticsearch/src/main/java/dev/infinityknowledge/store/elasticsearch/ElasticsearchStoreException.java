package dev.infinityknowledge.store.elasticsearch;

/**
 * Stable adapter exception that never exposes indexed content or credentials.
 */
public final class ElasticsearchStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an adapter failure.
     */
    public ElasticsearchStoreException(String message) {
        super(message);
    }

    /**
     * Creates an adapter failure with its technical cause.
     */
    public ElasticsearchStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
