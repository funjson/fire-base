package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.spi.objectstorage.DocumentSourceObject;

import java.time.Instant;
import java.util.UUID;

/** Safe original-source metadata that never includes a bucket, key or credential. */
public record SourceObjectResponse(
        UUID documentId,
        UUID revisionId,
        String documentStatus,
        String originalFileName,
        String mediaType,
        long contentLength,
        String checksumSha256,
        Instant storedAt,
        boolean previewable
) {

    /** Maps the authorized source catalog projection to its public representation. */
    public static SourceObjectResponse from(DocumentSourceObject value) {
        var source = value.sourceObject();
        return new SourceObjectResponse(
                value.documentId().value(),
                source.revisionId(),
                value.documentStatus().name(),
                source.originalFileName(),
                source.mediaType(),
                source.contentLength(),
                source.checksumSha256(),
                source.storedAt(),
                OriginalSourceServiceMediaTypes.previewable(source.mediaType())
        );
    }
}
