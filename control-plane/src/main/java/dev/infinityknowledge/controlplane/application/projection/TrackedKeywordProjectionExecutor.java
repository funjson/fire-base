package dev.infinityknowledge.controlplane.application.projection;

import dev.infinityknowledge.controlplane.config.ingestion.IndexingContract;
import dev.infinityknowledge.controlplane.config.ingestion.SpaceIndexingContractResolver;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.spi.keyword.KeywordIndex;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * 在 Elasticsearch 写入前后记录可恢复的关键词投影状态。
 *
 * <p>关键词和向量通道必须按租户和空间解析同一份文档处理配置契约，保证其结果能
 * 归属到同一个空间索引代际。</p>
 */
public final class TrackedKeywordProjectionExecutor implements ProjectionExecutor {

    private final KeywordIndex delegate;
    private final IndexProjectionStore projectionStore;
    private final EmbeddingSpec generationContract;
    private final IndexPhysicalContract physicalContract;
    private final SpaceIndexingContractResolver indexingContractResolver;
    private final Clock clock;

    /**
     * 创建带持久化状态跟踪的关键词投影执行器。
     */
    public TrackedKeywordProjectionExecutor(
            KeywordIndex delegate,
            IndexProjectionStore projectionStore,
            EmbeddingSpec generationContract,
            IndexPhysicalContract physicalContract,
            SpaceIndexingContractResolver indexingContractResolver,
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
        this.physicalContract = Objects.requireNonNull(
                physicalContract,
                "physicalContract must not be null"
        );
        this.indexingContractResolver = Objects.requireNonNull(
                indexingContractResolver,
                "indexingContractResolver must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ProjectionType projectionType() {
        return ProjectionType.KEYWORD;
    }

    @Override
    public void project(ProjectionSource source) {
        Objects.requireNonNull(source, "source must not be null");
        IndexingContract indexingContract = indexingContractResolver.resolve(
                source.document().tenantId(),
                source.document().spaceId()
        );
        UUID generationId = projectionStore.resolveActiveGeneration(
                source.document().tenantId(),
                source.document().spaceId(),
                generationContract,
                physicalContract,
                indexingContract.normalizerVersion(),
                indexingContract.chunkerVersion(),
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
