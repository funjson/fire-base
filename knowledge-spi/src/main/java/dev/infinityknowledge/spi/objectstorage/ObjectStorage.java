package dev.infinityknowledge.spi.objectstorage;

import java.io.InputStream;
import java.util.Optional;

/**
 * Stores immutable original source objects outside the transactional metadata store.
 */
public interface ObjectStorage {

    /**
     * Stores or idempotently replaces one logical object.
     *
     * @param request integrity and lifecycle metadata
     * @param content source stream with exactly {@code contentLength} bytes
     * @return provider-neutral stored metadata
     */
    StoredObjectMetadata put(ObjectWriteRequest request, InputStream content);

    /** Returns metadata when the tenant-scoped object exists. */
    Optional<StoredObjectMetadata> head(ObjectAddress address);

    /** Returns a closeable source stream when the tenant-scoped object exists. */
    Optional<StoredObject> get(ObjectAddress address);

    /** Deletes the exact tenant-scoped object; deleting a missing object is idempotent. */
    void delete(ObjectAddress address);
}
