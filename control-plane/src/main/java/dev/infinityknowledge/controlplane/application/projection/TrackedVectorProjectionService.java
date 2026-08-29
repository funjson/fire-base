package dev.infinityknowledge.controlplane.application.projection;

import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.controlplane.config.ingestion.IndexingContract;
import dev.infinityknowledge.controlplane.config.ingestion.SpaceIndexingContractResolver;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.vector.VectorProjectionService;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 在 GLM 与 Milvus 写入前后记录可恢复的向量投影状态。
 *
 * <p>每次投影按文档所属租户和空间解析当前文档处理配置契约；关键词与向量通道因此
 * 使用同一套 Parser 选择和 Chunker 语义。</p>
 */
public final class TrackedVectorProjectionService implements VectorProjectionService {

    private final VectorProjectionService delegate;
    private final IndexProjectionStore projectionStore;
    private final EmbeddingSpec embeddingSpec;
    private final IndexPhysicalContract physicalContract;
    private final SpaceIndexingContractResolver indexingContractResolver;
    private final Clock clock;

    /**
     * 创建带持久化状态跟踪的投影装饰器。
     */
    public TrackedVectorProjectionService(
            VectorProjectionService delegate,
            IndexProjectionStore projectionStore,
            EmbeddingSpec embeddingSpec,
            IndexPhysicalContract physicalContract,
            SpaceIndexingContractResolver indexingContractResolver,
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
    public void project(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        Objects.requireNonNull(document, "document must not be null");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        if (chunks.isEmpty()) {
            return;
        }
        IndexingContract indexingContract = indexingContractResolver.resolve(
                document.tenantId(),
                document.spaceId()
        );
        UUID generationId = projectionStore.resolveActiveGeneration(
                document.tenantId(),
                document.spaceId(),
                embeddingSpec,
                physicalContract,
                indexingContract.normalizerVersion(),
                indexingContract.chunkerVersion(),
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
