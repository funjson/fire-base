package dev.infinityknowledge.domain.document;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * References the immutable original source object retained for one document revision.
 *
 * <p>The storage identifier is intentionally opaque. Domain code must not depend on a
 * MinIO bucket or physical object key.</p>
 *
 * @param revisionId document revision that owns the source object
 * @param storageId opaque object-storage identifier
 * @param originalFileName original user-visible file name
 * @param mediaType normalized source media type
 * @param contentLength source size in bytes
 * @param checksumSha256 lowercase SHA-256 digest
 * @param storedAt time at which the source was retained
 */
public record SourceObjectReference(
        UUID revisionId,
        String storageId,
        String originalFileName,
        String mediaType,
        long contentLength,
        String checksumSha256,
        Instant storedAt
) {

    /** Validates the immutable source-object identity and integrity metadata. */
    public SourceObjectReference {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
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
        Objects.requireNonNull(storedAt, "storedAt must not be null");
    }
}
