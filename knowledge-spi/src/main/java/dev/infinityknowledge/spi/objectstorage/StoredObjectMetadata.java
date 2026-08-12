package dev.infinityknowledge.spi.objectstorage;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Storage-neutral metadata returned for a retained source object.
 *
 * @param address logical object address
 * @param storageId opaque adapter identifier persisted by the lifecycle layer
 * @param originalFileName original user-visible file name
 * @param mediaType stored media type
 * @param contentLength stored byte count
 * @param checksumSha256 source SHA-256 digest
 * @param etag provider entity tag, which is not treated as a content checksum
 * @param attributes non-sensitive source attributes
 * @param storedAt provider last-modified time
 */
public record StoredObjectMetadata(
        ObjectAddress address,
        String storageId,
        String originalFileName,
        String mediaType,
        long contentLength,
        String checksumSha256,
        String etag,
        Map<String, String> attributes,
        Instant storedAt
) {

    /** Validates values returned across the storage boundary. */
    public StoredObjectMetadata {
        Objects.requireNonNull(address, "address must not be null");
        storageId = DomainChecks.requiredText(storageId, "storageId", 256);
        originalFileName = DomainChecks.requiredText(
                originalFileName,
                "originalFileName",
                512
        );
        mediaType = DomainChecks.requiredText(mediaType, "mediaType", 128);
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must be non-negative");
        }
        checksumSha256 = DomainChecks.requiredText(
                checksumSha256,
                "checksumSha256",
                64
        ).toLowerCase(java.util.Locale.ROOT);
        if (!checksumSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must contain 64 hexadecimal characters");
        }
        etag = DomainChecks.requiredText(etag, "etag", 256);
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        Objects.requireNonNull(storedAt, "storedAt must not be null");
    }
}
