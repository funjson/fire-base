package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.ingestion.HeadingAwareChunker;
import dev.infinityknowledge.ingestion.MarkdownElementParser;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.keyword.KeywordIndex;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Adds durable keyword projection state around Elasticsearch writes.
 */
public final class TrackedKeywordProjectionExecutor implements ProjectionExecutor {

    private final KeywordIndex delegate;
    private final IndexProjectionStore projectionStore;
    private final EmbeddingSpec generationContract;
    private final String generation;
    private final Clock clock;

    /**
     * Creates the tracked executor.
     */
    public TrackedKeywordProjectionExecutor(
            KeywordIndex delegate,
            IndexProjectionStore projectionStore,
            EmbeddingSpec generationContract,
            String generation,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.projectionStore = Objects.requireNonNull(
                projectionStore,
                "projectionStore must not be null"
        );
        this.generationContract = Objects.requireNonNull(
                generationContract,
                "generationContract must not be null"
        );
        this.generation = Objects.requireNonNull(generation, "generation must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ProjectionType projectionType() {
        return ProjectionType.KEYWORD;
    }

    @Override
    public void project(ProjectionSource source) {
        Objects.requireNonNull(source, "source must not be null");
        UUID generationId = projectionStore.resolveActiveGeneration(
                source.document().tenantId(),
                source.document().spaceId(),
                generationContract,
                generation,
                MarkdownElementParser.VERSION,
                HeadingAwareChunker.VERSION,
                clock.instant()
        );
        record(source, generationId, ProjectionStatus.PENDING);
        try {
            delegate.upsert(source);
            record(source, generationId, ProjectionStatus.SUCCEEDED);
        } catch (RuntimeException projectionFailure) {
            try {
                record(source, generationId, ProjectionStatus.FAILED);
            } catch (RuntimeException trackingFailure) {
                projectionFailure.addSuppressed(trackingFailure);
            }
            throw projectionFailure;
        }
    }

    private void record(
            ProjectionSource source,
            UUID generationId,
            ProjectionStatus status
    ) {
        projectionStore.recordProjectionStatus(
                source.document().tenantId(),
                generationId,
                source.document().id(),
                source.chunks().getFirst().revisionId(),
                ProjectionType.KEYWORD,
                status,
                clock.instant()
        );
    }
}
