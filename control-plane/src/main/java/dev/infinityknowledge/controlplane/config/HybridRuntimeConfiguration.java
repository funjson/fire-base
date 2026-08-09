package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.vector.VectorIndex;
import dev.infinityknowledge.store.elasticsearch.ElasticsearchKeywordIndex;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Fails startup when a runtime explicitly declares strict hybrid retrieval but
 * one of its required channels is missing or unavailable.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.retrieval",
        name = "mode",
        havingValue = "hybrid"
)
public class HybridRuntimeConfiguration {

    /**
     * Probes all dependencies required by strict hybrid retrieval.
     */
    @Bean
    InitializingBean hybridRuntimeReadiness(
            RetrievalProperties retrievalProperties,
            VectorProperties vectorProperties,
            ElasticsearchProperties elasticsearchProperties,
            EmbeddingProperties embeddingProperties,
            ObjectProvider<VectorIndex> vectorIndexProvider,
            ObjectProvider<ElasticsearchKeywordIndex> keywordIndexProvider,
            ObjectProvider<EmbeddingProvider> embeddingProvider
    ) {
        validate(
                retrievalProperties,
                vectorProperties,
                elasticsearchProperties,
                embeddingProperties
        );
        return () -> {
            VectorIndex vectorIndex = required(
                    vectorIndexProvider.getIfAvailable(),
                    "Milvus vector channel"
            );
            ElasticsearchKeywordIndex keywordIndex = required(
                    keywordIndexProvider.getIfAvailable(),
                    "Elasticsearch keyword channel"
            );
            EmbeddingProvider provider = required(
                    embeddingProvider.getIfAvailable(),
                    "GLM embedding provider"
            );
            EmbeddingSpec spec = new EmbeddingSpec(
                    embeddingProperties.provider(),
                    embeddingProperties.model(),
                    embeddingProperties.dimensions()
            );
            keywordIndex.ensureReady();
            vectorIndex.ensureGeneration(spec, embeddingProperties.generation());
            provider.embed(List.of("Infinity Knowledge readiness probe"), spec);
        };
    }

    static void validate(
            RetrievalProperties retrievalProperties,
            VectorProperties vectorProperties,
            ElasticsearchProperties elasticsearchProperties,
            EmbeddingProperties embeddingProperties
    ) {
        if (retrievalProperties.mode() != RetrievalProperties.Mode.HYBRID) {
            throw new IllegalStateException(
                    "hybrid readiness may only run in HYBRID retrieval mode"
            );
        }
        if (!vectorProperties.enabled()) {
            throw new IllegalStateException(
                    "HYBRID retrieval requires infinity.knowledge.vector.enabled=true"
            );
        }
        if (!elasticsearchProperties.enabled()) {
            throw new IllegalStateException(
                    "HYBRID retrieval requires "
                            + "infinity.knowledge.keyword.elasticsearch.enabled=true"
            );
        }
        if (embeddingProperties.apiKey().isBlank()
                || embeddingProperties.apiKey().contains("${")) {
            throw new IllegalStateException(
                    "HYBRID retrieval requires a resolved GLM embedding API key"
            );
        }
    }

    private static <T> T required(T value, String channel) {
        if (value == null) {
            throw new IllegalStateException(channel + " is not configured");
        }
        return value;
    }
}
