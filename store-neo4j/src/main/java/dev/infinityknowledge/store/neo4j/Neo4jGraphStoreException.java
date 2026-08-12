package dev.infinityknowledge.store.neo4j;

/**
 * Reports Neo4j schema, projection and retrieval failures without leaking credentials.
 */
public final class Neo4jGraphStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a failure with a safe operation-level message.
     */
    public Neo4jGraphStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
