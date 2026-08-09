package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.controlplane.application.TrackedKeywordProjectionExecutor;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
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
 * Wires the optional Elasticsearch keyword projection and retrieval channel.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.keyword.elasticsearch",
        name = "enabled",
        havingValue = "true"
)
public class ElasticsearchRuntimeConfiguration {

    /**
     * Creates the isolated Elasticsearch HTTP client.
     */
    @Bean
    HttpClient elasticsearchHttpClient(ElasticsearchProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /**
     * Creates the shared read/write adapter.
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
     * Adds tracked, retryable keyword projection to the generic worker.
     */
    @Bean
    ProjectionExecutor keywordProjectionExecutor(
            ElasticsearchKeywordIndex keywordIndex,
            IndexProjectionStore projectionStore,
            EmbeddingProperties embeddingProperties,
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
                embeddingProperties.generation(),
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
