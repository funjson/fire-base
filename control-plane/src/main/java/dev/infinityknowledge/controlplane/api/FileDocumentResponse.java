package dev.infinityknowledge.controlplane.api;

import java.util.List;
import java.util.UUID;

/** Result of publishing one retained rich-document source. */
public record FileDocumentResponse(
        UUID documentId,
        UUID revisionId,
        boolean changed,
        int elementCount,
        int chunkCount,
        String originalFileName,
        String mediaType,
        long contentLength,
        String vectorStatus,
        List<String> warnings
) {

    /** Defensively copies stable projection warnings. */
    public FileDocumentResponse {
        warnings = List.copyOf(warnings);
    }
}
