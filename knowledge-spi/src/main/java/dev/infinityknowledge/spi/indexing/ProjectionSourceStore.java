package dev.infinityknowledge.spi.indexing;

/**
 * Loads immutable source material referenced by a projection job.
 */
public interface ProjectionSourceStore {

    /**
     * Loads the exact revision referenced by a claimed job.
     */
    ProjectionSource load(ProjectionJob job);
}
