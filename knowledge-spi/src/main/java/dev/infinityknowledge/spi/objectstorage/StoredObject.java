package dev.infinityknowledge.spi.objectstorage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Streaming content returned by object storage.
 *
 * <p>Callers must close this value after consuming the stream.</p>
 *
 * @param metadata stored object metadata
 * @param content provider response stream
 */
public record StoredObject(
        StoredObjectMetadata metadata,
        InputStream content
) implements AutoCloseable {

    /** Rejects incomplete provider responses. */
    public StoredObject {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    /** Closes the provider response stream. */
    @Override
    public void close() throws IOException {
        content.close();
    }
}
