package dev.infinityknowledge.controlplane.application.projection;

import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.vector.VectorProjectionService;

import java.util.Objects;

/**
 * 将既有向量投影服务适配为通用任务 Worker 的执行器。
 */
public final class VectorProjectionExecutor implements ProjectionExecutor {

    private final VectorProjectionService delegate;

    /**
     * 创建向量投影执行器。
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
