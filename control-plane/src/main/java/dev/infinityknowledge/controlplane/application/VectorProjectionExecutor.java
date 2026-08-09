package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.vector.VectorProjectionService;

import java.util.Objects;

/**
 * Adapts the established vector projection service to the generic job worker.
 */
public final class VectorProjectionExecutor implements ProjectionExecutor {

    private final VectorProjectionService delegate;

    /**
     * Creates the executor.
     */
    public VectorProjectionExecutor(VectorProjectionService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public ProjectionType projectionType() {
        return ProjectionType.VECTOR;
    }

    @Override
    public void project(ProjectionSource source) {
        Objects.requireNonNull(source, "source must not be null");
        delegate.project(source.document(), source.chunks());
    }
}
