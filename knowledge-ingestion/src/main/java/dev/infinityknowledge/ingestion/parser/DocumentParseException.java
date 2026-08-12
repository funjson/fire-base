package dev.infinityknowledge.ingestion.parser;

/** Stable parsing failure that can be mapped to an ingestion validation error. */
public final class DocumentParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates a parsing failure without exposing source content. */
    public DocumentParseException(String message) {
        super(message);
    }

    /** Creates a parsing failure with its technical cause. */
    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
