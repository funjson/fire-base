package dev.infinityknowledge.spi.vector;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;

import java.util.List;

/**
 * Stores and retrieves tenant-scoped chunk vectors without exposing a vendor SDK.
 */
public interface VectorIndex {

    /**
     * Creates the physical collection and indexes for an immutable embedding generation.
     *
     * @param spec embedding model contract
     * @param generation stable generation identifier
     */
    void ensureGeneration(EmbeddingSpec spec, String generation);

    /**
     * Idempotently replaces vector projections having the same primary keys.
     *
     * @param records vector projections
     */
    void upsert(List<VectorIndexRecord> records);

    /**
     * Searches only inside the already-authorized tenant and space scope.
     *
     * @param request secure vector search request
     * @return channel-ranked candidates
     */
    List<RetrievalCandidate> search(VectorSearchRequest request);
}
