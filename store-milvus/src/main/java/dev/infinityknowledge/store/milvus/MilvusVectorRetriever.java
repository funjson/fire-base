package dev.infinityknowledge.store.milvus;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.vector.VectorIndex;
import dev.infinityknowledge.spi.vector.VectorSearchRequest;

import java.util.List;
import java.util.Objects;

/**
 * Vector retrieval channel that embeds the normalized query with the active generation.
 */
public final class MilvusVectorRetriever implements Retriever {

    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingSpec embeddingSpec;
    private final String generation;
    private final VectorIndex vectorIndex;

    /**
     * Creates the retriever.
     */
    public MilvusVectorRetriever(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            String generation,
            VectorIndex vectorIndex
    ) {
        this.embeddingProvider = Objects.requireNonNull(
                embeddingProvider,
                "embeddingProvider must not be null"
        );
        this.embeddingSpec = Objects.requireNonNull(
                embeddingSpec,
                "embeddingSpec must not be null"
        );
        this.generation = Objects.requireNonNull(generation, "generation must not be null");
        this.vectorIndex = Objects.requireNonNull(vectorIndex, "vectorIndex must not be null");
    }

    @Override
    public RetrievalChannel channel() {
        return RetrievalChannel.VECTOR;
    }

    @Override
    public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.accessScope().deniesAll()) {
            return List.of();
        }
        var vector = embeddingProvider.embed(
                List.of(request.plan().normalizedQuery()),
                embeddingSpec
        ).getFirst();
        return vectorIndex.search(new VectorSearchRequest(
                request.accessScope(),
                embeddingSpec,
                generation,
                request.query().filters(),
                vector.values(),
                request.plan().candidateLimit()
        ));
    }
}
