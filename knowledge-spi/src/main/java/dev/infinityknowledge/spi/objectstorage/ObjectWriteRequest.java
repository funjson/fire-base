package dev.infinityknowledge.spi.objectstorage;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.Map;
import java.util.Objects;

/**
 * Metadata supplied while storing an immutable original source object.
 *
 * @param address logical object address
 * @param originalFileName original user-visible file name
 * @param mediaType source media type
 * @param contentLength exact number of bytes to upload
 * @param checksumSha256 lowercase SHA-256 digest of the source bytes
 * @param attributes non-sensitive source attributes
 */
public record ObjectWriteRequest(
        ObjectAddress address,
        String originalFileName,
        String mediaType,
        long contentLength,
        String checksumSha256,
        Map<String, String> attributes
) {

    /** Validates integrity metadata and defensively copies attributes. */
    public ObjectWriteRequest {
        Objects.requireNonNull(address, "address must not be null");
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
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        attributes.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z0-9][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("attribute names must be lowercase HTTP-safe tokens");
            }
            if (value == null || value.length() > 1_024
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("attribute values must be safe single-line metadata");
            }
        });
    }
}
