package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.controlplane.application.TrackedVectorProjectionService;
import dev.infinityknowledge.controlplane.application.VectorProjectionExecutor;
import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuEmbeddingConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuEmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
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
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Clock;

/**
 * Wires the optional GLM and Milvus vector channel.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.vector",
        name = "enabled",
        havingValue = "true"
)
public class VectorRuntimeConfiguration {

    /**
     * Builds the immutable embedding contract used by ingestion and retrieval.
     */
    @Bean
    EmbeddingSpec embeddingSpec(EmbeddingProperties properties) {
        return new EmbeddingSpec(
                properties.provider(),
                properties.model(),
                properties.dimensions()
        );
    }

    /**
     * Builds a bounded JDK HTTP client with an optional local proxy.
     */
    @Bean
    HttpClient embeddingHttpClient(EmbeddingProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout());
        if (!properties.proxyHost().isEmpty()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    properties.proxyHost(),
                    properties.proxyPort()
            )));
        }
        return builder.build();
    }

    /**
     * Creates the GLM embedding provider. Credentials are never logged.
     */
    @Bean
    EmbeddingProvider embeddingProvider(
            EmbeddingProperties properties,
            HttpClient embeddingHttpClient
    ) {
        return new ZhipuEmbeddingProvider(
                new ZhipuEmbeddingConfig(
                        properties.endpoint(),
                        properties.apiKey(),
                        properties.requestTimeout(),
                        properties.maxBatchSize(),
                        properties.maxAttempts(),
                        properties.initialBackoff()
                ),
                embeddingHttpClient,
                JsonMapper.builder().build(),
                new ThreadRetrySleeper()
        );
    }

    /**
     * Creates the Milvus client.
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
     * Registers the vendor-neutral vector index.
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
     * Registers vector projection for committed revisions.
     */
    @Bean
    VectorProjectionService vectorProjectionService(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            EmbeddingProperties properties,
            VectorIndex vectorIndex,
            IndexProjectionStore projectionStore,
            ActiveRevisionGuard activeRevisionGuard,
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
                properties.generation(),
                clock
        );
    }

    /**
     * Registers vector work with the generic projection worker.
     */
    @Bean
    ProjectionExecutor vectorProjectionExecutor(
            VectorProjectionService vectorProjectionService
    ) {
        return new VectorProjectionExecutor(vectorProjectionService);
    }

    /**
     * Adds the Milvus vector retrieval channel to the runtime ensemble.
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
