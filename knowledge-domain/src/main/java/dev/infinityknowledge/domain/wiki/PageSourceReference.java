package dev.infinityknowledge.domain.wiki;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.DocumentId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable provenance from a generated page claim back to one source chunk.
 */
public record PageSourceReference(
        DocumentId documentId,
        UUID revisionId,
        UUID chunkId,
        List<String> sectionPath,
        String contentHash,
        int authority
) {

    public PageSourceReference {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        sectionPath = List.copyOf(Objects.requireNonNull(
                sectionPath,
                "sectionPath must not be null"
        ));
        if (sectionPath.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 512)) {
            throw new IllegalArgumentException(
                    "sectionPath must contain non-blank values up to 512 characters"
            );
        }
        contentHash = DomainChecks.requiredText(contentHash, "contentHash", 128);
        if (authority < 0 || authority > 100) {
            throw new IllegalArgumentException("authority must be between 0 and 100");
        }
    }
}
