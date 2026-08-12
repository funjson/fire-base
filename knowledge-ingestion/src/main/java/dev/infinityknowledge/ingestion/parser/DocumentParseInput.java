package dev.infinityknowledge.ingestion.parser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded immutable input passed to exactly one selected document parser.
 *
 * @param revisionId target immutable document revision
 * @param mediaType normalized media type without parameters
 * @param fileName original file name used only for parser selection and metadata
 * @param sourceBytes already bounded source bytes
 * @param limits parsing budgets
 */
public record DocumentParseInput(
        UUID revisionId,
        String mediaType,
        String fileName,
        byte[] sourceBytes,
        DocumentParseLimits limits
) {

    /** Defensively copies source bytes and rejects incomplete requests. */
    public DocumentParseInput {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        sourceBytes = Objects.requireNonNull(sourceBytes, "sourceBytes must not be null").clone();
        limits = Objects.requireNonNull(limits, "limits must not be null");
        if (sourceBytes.length > limits.maximumSourceBytes()) {
            throw new DocumentParseException("source exceeds maximumSourceBytes");
        }
    }

    /** Returns a new stream over the immutable bounded source. */
    public InputStream openStream() {
        return new ByteArrayInputStream(sourceBytes);
    }

    /** Returns a defensive copy for libraries that require a byte array. */
    @Override
    public byte[] sourceBytes() {
        return sourceBytes.clone();
    }
}
