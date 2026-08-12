package dev.infinityknowledge.store.minio;

/** Stable adapter failure that does not expose credentials or object content. */
public final class MinioObjectStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an adapter failure with its technical cause. */
    public MinioObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
