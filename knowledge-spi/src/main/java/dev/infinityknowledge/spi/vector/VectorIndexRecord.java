package dev.infinityknowledge.spi.vector;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;

import java.util.List;
import java.util.Objects;

/**
 * Immutable projection written to a vector index.
 *
 * @param chunk source chunk
 * @param title current document title
 * @param sourceUri traceable source URI
 * @param sourceType normalized source type used by cross-channel filtering
 * @param language normalized BCP 47 language tag
 * @param authority document authority level
 * @param embeddingSpec embedding model contract
 * @param generation immutable index generation
 * @param vector chunk vector
 */
public record VectorIndexRecord(
        KnowledgeChunk chunk,
        String title,
        String sourceUri,
        String sourceType,
        String language,
        int authority,
        EmbeddingSpec embeddingSpec,
        String generation,
        List<Double> vector
) {

    /**
     * Validates provenance and vector dimensions before an infrastructure write.
     */
    public VectorIndexRecord {
        Objects.requireNonNull(chunk, "chunk must not be null");
        title = DomainChecks.requiredText(title, "title", 512);
        sourceUri = DomainChecks.requiredText(sourceUri, "sourceUri", 2048);
        sourceType = DomainChecks.requiredText(sourceType, "sourceType", 32);
        language = DomainChecks.requiredText(language, "language", 32);
        if (authority < 0 || authority > 100) {
            throw new IllegalArgumentException("authority must be between 0 and 100");
        }
        Objects.requireNonNull(embeddingSpec, "embeddingSpec must not be null");
        generation = DomainChecks.requiredText(generation, "generation", 64);
        vector = List.copyOf(Objects.requireNonNull(vector, "vector must not be null"));
        if (vector.size() != embeddingSpec.dimensions()) {
            throw new IllegalArgumentException("vector dimensions differ from embedding spec");
        }
        if (vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("vector contains invalid values");
        }
    }
}
