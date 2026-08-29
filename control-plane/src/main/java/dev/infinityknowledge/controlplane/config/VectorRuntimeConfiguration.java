package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.controlplane.application.projection.TrackedVectorProjectionService;
import dev.infinityknowledge.controlplane.application.projection.VectorProjectionExecutor;
import dev.infinityknowledge.controlplane.config.ingestion.SpaceIndexingContractResolver;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.retrieval.Retriever;
import dev.infinityknowledge.spi.vector.VectorIndex;
import dev.infinityknowledge.spi.vector.VectorProjectionService;
import dev.infinityknowledge.store.milvus.DefaultVectorProjectionService;
import dev.infinityknowledge.store.milvus.MilvusVectorIndex;
import dev.infinityknowledge.store.milvus.MilvusVectorRetriever;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/**
 * 装配可选 Milvus 向量投影与检索通道。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.vector",
        name = "enabled",
        havingValue = "true"
)
public class VectorRuntimeConfiguration {

    /**
     * 创建 Milvus 客户端。
     */
    @Bean(destroyMethod = "close")
    MilvusClientV2 milvusClient(VectorProperties properties) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(properties.uri())
                .connectTimeoutMs(properties.connectTimeout().toMillis())
                .rpcDeadlineMs(properties.rpcDeadline().toMillis());
        if (!properties.token().isEmpty()) {
            builder.token(properties.token());
        }
        return new MilvusClientV2(builder.build());
    }

    /**
     * 注册厂商无关的向量索引端口。
     */
    @Bean
    VectorIndex vectorIndex(
            MilvusClientV2 client,
            VectorProperties properties,
            ActiveRevisionGuard activeRevisionGuard
    ) {
        return new MilvusVectorIndex(
                client,
                properties.collectionPrefix(),
                properties.partitionCount(),
                activeRevisionGuard
        );
    }

    /**
     * 为已提交修订注册向量投影能力。
     */
    @Bean
    VectorProjectionService vectorProjectionService(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            EmbeddingProperties properties,
            IndexPhysicalContract physicalContract,
            VectorIndex vectorIndex,
            IndexProjectionStore projectionStore,
            ActiveRevisionGuard activeRevisionGuard,
            SpaceIndexingContractResolver indexingContractResolver,
            Clock clock
    ) {
        return new TrackedVectorProjectionService(
                new DefaultVectorProjectionService(
                        embeddingProvider,
                        embeddingSpec,
                        properties.generation(),
                        vectorIndex,
                        activeRevisionGuard
                ),
                projectionStore,
                embeddingSpec,
                physicalContract,
                indexingContractResolver,
                clock
        );
    }

    /**
     * 把向量任务接入通用投影 Worker。
     */
    @Bean
    ProjectionExecutor vectorProjectionExecutor(
            VectorProjectionService vectorProjectionService
    ) {
        return new VectorProjectionExecutor(vectorProjectionService);
    }

    /**
     * 把 Milvus 向量检索加入 Runtime 召回集合。
     */
    @Bean
    Retriever milvusVectorRetriever(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            EmbeddingProperties properties,
            VectorIndex vectorIndex
    ) {
        return new MilvusVectorRetriever(
                embeddingProvider,
                embeddingSpec,
                properties.generation(),
                vectorIndex
        );
    }
}
