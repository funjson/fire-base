package dev.infinityknowledge.spi.vector;

import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;

import java.util.List;

/**
 * Projects a committed document revision into the active vector generation.
 */
public interface VectorProjectionService {

    /**
     * Embeds and indexes all chunks from one committed revision.
     *
     * @param document source document
     * @param chunks committed chunks
     */
    void project(KnowledgeDocument document, List<KnowledgeChunk> chunks);
}
