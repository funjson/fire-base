package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.Objects;

/**
 * 在权威文档修订发布事务内同时建立 Space 的真实索引基线代际。
 *
 * <p>PostgreSQL 关键词召回直接读取事务库，不会经过外部投影 Worker；若只依赖外部
 * ProjectionExecutor 创建代际，默认安装的新 Space 将永远没有可检索版本。本装饰器让
 * 文档、活动修订和代际要么一起提交、要么一起回滚，同时仍由同一个
 * {@link IndexProjectionStore} 维护唯一代际规则。</p>
 */
public final class IndexGenerationTrackingKnowledgeWriter implements KnowledgeWriter {

    private final KnowledgeWriter delegate;
    private final IndexProjectionStore projectionStore;
    private final EmbeddingSpec embeddingSpec;
    private final IndexPhysicalContract physicalContract;
    private final SpaceIndexingContractResolver indexingContractResolver;
    private final TransactionOperations transaction;
    private final Clock clock;

    /** 创建参与文档发布事务的代际跟踪写入器。 */
    public IndexGenerationTrackingKnowledgeWriter(
            KnowledgeWriter delegate,
            IndexProjectionStore projectionStore,
            EmbeddingSpec embeddingSpec,
            IndexPhysicalContract physicalContract,
            SpaceIndexingContractResolver indexingContractResolver,
            TransactionOperations transaction,
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
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public KnowledgeWriteResult write(KnowledgeWriteBatch batch) {
        Objects.requireNonNull(batch, "batch must not be null");
        KnowledgeWriteResult result = transaction.execute(status -> {
            KnowledgeWriteResult persisted = delegate.write(batch);
            var document = batch.document();
            IndexingContract contract = indexingContractResolver.resolve(
                    document.tenantId(),
                    document.spaceId()
            );
            projectionStore.resolveActiveGeneration(
                    document.tenantId(),
                    document.spaceId(),
                    embeddingSpec,
                    physicalContract,
                    contract.normalizerVersion(),
                    contract.chunkerVersion(),
                    clock.instant()
            );
            return persisted;
        });
        return Objects.requireNonNull(result, "knowledge write transaction returned no result");
    }

}
