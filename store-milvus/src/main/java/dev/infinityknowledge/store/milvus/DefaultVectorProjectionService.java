package dev.infinityknowledge.store.milvus;

import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.vector.VectorIndex;
import dev.infinityknowledge.spi.vector.VectorIndexRecord;
import dev.infinityknowledge.spi.vector.VectorProjectionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generates chunk embeddings and publishes one immutable revision to Milvus.
 */
public final class DefaultVectorProjectionService implements VectorProjectionService {

    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingSpec embeddingSpec;
    private final String generation;
    private final VectorIndex vectorIndex;
    private final ActiveRevisionGuard activeRevisionGuard;

    /**
     * Creates the projection service.
     */
    public DefaultVectorProjectionService(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            String generation,
            VectorIndex vectorIndex,
            ActiveRevisionGuard activeRevisionGuard
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
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
    }

    @Override
    public void project(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        Objects.requireNonNull(document, "document must not be null");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        if (chunks.isEmpty()) {
            return;
        }
        var revisionId = chunks.getFirst().revisionId();
        if (!activeRevisionGuard.isActive(document.tenantId(), document.id(), revisionId)) {
            return;
        }
        List<EmbeddingVector> vectors = embeddingProvider.embed(
                chunks.stream().map(KnowledgeChunk::content).toList(),
                embeddingSpec
        );
        List<VectorIndexRecord> records = new ArrayList<>(chunks.size());
        for (EmbeddingVector vector : vectors) {
            KnowledgeChunk chunk = chunks.get(vector.index());
            records.add(new VectorIndexRecord(
                    chunk,
                    document.title(),
                    document.source().uri(),
                    document.source().type().name(),
                    language(document, chunk),
                    document.authority(),
                    embeddingSpec,
                    generation,
                    vector.values()
            ));
        }
        // Embedding is the slow part of projection, so the document head may have changed.
        if (!activeRevisionGuard.isActive(document.tenantId(), document.id(), revisionId)) {
            return;
        }
        vectorIndex.upsert(records);
    }

    private static String language(
            KnowledgeDocument document,
            KnowledgeChunk chunk
    ) {
        String language = document.metadata().get("language");
        if (language == null || language.isBlank()) {
            language = chunk.metadata().get("language");
        }
        return language == null || language.isBlank() ? "und" : language.strip();
    }
}
