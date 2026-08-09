package dev.infinityknowledge.spi.vector;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;

import java.util.List;
import java.util.Objects;

/**
 * Secure vector query carrying the policy-engine scope into the storage adapter.
 *
 * @param accessScope authorized tenant, spaces and optional documents
 * @param embeddingSpec embedding model contract
 * @param generation immutable index generation
 * @param vector query vector
 * @param limit maximum result count
 */
public record VectorSearchRequest(
        AccessScope accessScope,
        EmbeddingSpec embeddingSpec,
        String generation,
        List<Double> vector,
        int limit
) {

    /**
     * Validates that vector and generation agree with the target collection.
     */
    public VectorSearchRequest {
        Objects.requireNonNull(accessScope, "accessScope must not be null");
        Objects.requireNonNull(embeddingSpec, "embeddingSpec must not be null");
        generation = DomainChecks.requiredText(generation, "generation", 64);
        vector = List.copyOf(Objects.requireNonNull(vector, "vector must not be null"));
        if (vector.size() != embeddingSpec.dimensions()) {
            throw new IllegalArgumentException("query vector dimensions differ from embedding spec");
        }
        if (vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("query vector contains invalid values");
        }
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
    }
}
