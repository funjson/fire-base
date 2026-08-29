package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.controlplane.application.projection.TrackedKeywordProjectionExecutor;
import dev.infinityknowledge.controlplane.config.ingestion.SpaceIndexingContractResolver;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.store.elasticsearch.ElasticsearchConfig;
import dev.infinityknowledge.store.elasticsearch.ElasticsearchKeywordIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * 装配可选的 Elasticsearch 关键词投影与检索通道。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.keyword.elasticsearch",
        name = "enabled",
        havingValue = "true"
)
public class ElasticsearchRuntimeConfiguration {

    /**
     * 创建隔离的 Elasticsearch HTTP 客户端。
     */
    @Bean
    HttpClient elasticsearchHttpClient(ElasticsearchProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /**
     * 创建读写共用的 Elasticsearch Adapter。
     */
    @Bean
    ElasticsearchKeywordIndex elasticsearchKeywordIndex(
            ElasticsearchProperties properties,
            HttpClient elasticsearchHttpClient,
            ActiveRevisionGuard activeRevisionGuard
    ) {
        return new ElasticsearchKeywordIndex(
                elasticsearchHttpClient,
                JsonMapper.builder().build(),
                new ElasticsearchConfig(
                        URI.create(properties.endpoint()),
                        properties.indexName(),
                        authorization(properties),
                        properties.requestTimeout()
                ),
                activeRevisionGuard
        );
    }

    /**
     * 将 Elasticsearch 实际索引名与映射纳入所有投影和检索共用的物理合同。
     */
    @Bean
    IndexPhysicalContract elasticsearchIndexPhysicalContract(
            EmbeddingProperties embeddingProperties,
            ElasticsearchKeywordIndex keywordIndex
    ) {
        return IndexPhysicalContract.withKeywordTarget(
                embeddingProperties.generation(),
                keywordIndex.physicalTargetFingerprint()
        );
    }

    /**
     * 将可跟踪、可重试的关键词投影接入通用 Worker。
     */
    @Bean
    ProjectionExecutor keywordProjectionExecutor(
            ElasticsearchKeywordIndex keywordIndex,
            IndexProjectionStore projectionStore,
            EmbeddingProperties embeddingProperties,
            IndexPhysicalContract physicalContract,
            SpaceIndexingContractResolver indexingContractResolver,
            Clock clock
    ) {
        return new TrackedKeywordProjectionExecutor(
                keywordIndex,
                projectionStore,
                new EmbeddingSpec(
                        embeddingProperties.provider(),
                        embeddingProperties.model(),
                        embeddingProperties.dimensions()
                ),
                physicalContract,
                indexingContractResolver,
                clock
        );
    }

    private static String authorization(ElasticsearchProperties properties) {
        if (!properties.apiKey().isEmpty()) {
            return "ApiKey " + properties.apiKey();
        }
        if (!properties.username().isEmpty()) {
            String credentials = properties.username() + ":" + properties.password();
            return "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8)
            );
        }
        return "";
    }
}
