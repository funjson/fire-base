package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.runtime.rerank.CosineEmbeddingReranker;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.retrieval.Reranker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the optional real reranker without coupling it to a specific embedding vendor.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.retrieval.reranker",
        name = "enabled",
        havingValue = "true"
)
public class EmbeddingRerankerConfiguration {

    /**
     * Uses the existing embedding provider and model contract for deterministic cosine reranking.
     */
    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    Reranker cosineEmbeddingReranker(
            ObjectProvider<EmbeddingProvider> embeddingProvider,
            ObjectProvider<EmbeddingSpec> embeddingSpec,
            RerankerProperties properties
    ) {
        EmbeddingProvider provider = required(
                embeddingProvider.getIfAvailable(),
                "EmbeddingProvider"
        );
        EmbeddingSpec spec = required(
                embeddingSpec.getIfAvailable(),
                "EmbeddingSpec"
        );
        return new CosineEmbeddingReranker(
                provider,
                spec,
                properties.maxCandidates(),
                properties.maxQueryCharacters(),
                properties.maxCandidateCharacters(),
                properties.maxTotalCharacters()
        );
    }

    private static <T> T required(T value, String dependency) {
        if (value == null) {
            throw new IllegalStateException(
                    "Embedding reranker requires " + dependency
                            + "; enable the vector embedding runtime or provide a custom bean"
            );
        }
        return value;
    }
}
