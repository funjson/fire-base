package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;

import java.util.List;
import java.util.Objects;

/**
 * Immutable source material loaded for an external projection.
 *
 * @param document governed document metadata
 * @param chunks chunks belonging to the requested revision
 */
public record ProjectionSource(
        KnowledgeDocument document,
        List<KnowledgeChunk> chunks
) {

    /**
     * Defensively copies source chunks.
     */
    public ProjectionSource {
        Objects.requireNonNull(document, "document must not be null");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("projection source must contain chunks");
        }
    }
}
