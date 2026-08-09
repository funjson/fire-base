package dev.infinityknowledge.spi.indexing;

/**
 * Executes one type of idempotent external index projection.
 */
public interface ProjectionExecutor {

    /**
     * Projection type handled by this executor.
     */
    ProjectionType projectionType();

    /**
     * Projects one immutable document revision.
     */
    void project(ProjectionSource source);
}
