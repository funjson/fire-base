package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.ingestion.HeadingAwareChunker;
import dev.infinityknowledge.ingestion.MarkdownElementParser;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.vector.VectorProjectionService;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Adds durable projection status around the external GLM and Milvus operation.
 */
public final class TrackedVectorProjectionService implements VectorProjectionService {

    private final VectorProjectionService delegate;
    private final IndexProjectionStore projectionStore;
    private final EmbeddingSpec embeddingSpec;
    private final String generation;
    private final Clock clock;

    /**
     * Creates the tracked decorator.
     */
    public TrackedVectorProjectionService(
            VectorProjectionService delegate,
            IndexProjectionStore projectionStore,
            EmbeddingSpec embeddingSpec,
            String generation,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.projectionStore = Objects.requireNonNull(
                projectionStore,
                "projectionStore must not be null"
        );
        this.embeddingSpec = Objects.requireNonNull(
                embeddingSpec,
                "embeddingSpec must not be null"
        );
        this.generation = Objects.requireNonNull(generation, "generation must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void project(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        Objects.requireNonNull(document, "document must not be null");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        if (chunks.isEmpty()) {
            return;
        }
        UUID generationId = projectionStore.resolveActiveGeneration(
                document.tenantId(),
                document.spaceId(),
                embeddingSpec,
                generation,
                MarkdownElementParser.VERSION,
                HeadingAwareChunker.VERSION,
                clock.instant()
        );
        record(document, chunks, generationId, ProjectionStatus.PENDING);
        try {
            delegate.project(document, chunks);
            record(document, chunks, generationId, ProjectionStatus.SUCCEEDED);
        } catch (RuntimeException projectionFailure) {
            try {
                record(document, chunks, generationId, ProjectionStatus.FAILED);
            } catch (RuntimeException trackingFailure) {
                projectionFailure.addSuppressed(trackingFailure);
            }
            throw projectionFailure;
        }
    }

    private void record(
            KnowledgeDocument document,
            List<KnowledgeChunk> chunks,
            UUID generationId,
            ProjectionStatus status
    ) {
        projectionStore.recordVectorStatus(
                document.tenantId(),
                generationId,
                document.id(),
                chunks.getFirst().revisionId(),
                status,
                clock.instant()
        );
    }
}
