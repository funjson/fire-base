package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.retrieval.rerank.CosineEmbeddingReranker;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.retrieval.Reranker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册复用现有 EmbeddingProvider 的余弦精排实现。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "infinity.knowledge.retrieval.reranker",
        name = "enabled",
        havingValue = "true"
)
public class EmbeddingRerankerConfiguration {

    /**
     * 使用现有向量模型执行确定性余弦排序，主要作为兼容基线。
     *
     * @param embeddingProvider 向量 Provider
     * @param embeddingSpec 向量模型契约
     * @param properties 精排资源预算
     * @return 余弦精排器
     */
    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    @ConditionalOnProperty(
            prefix = "infinity.knowledge.retrieval.reranker",
            name = "provider",
            havingValue = "embedding",
            matchIfMissing = true
    )
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
                            + "; enable infinity.knowledge.embedding or provide a custom bean"
            );
        }
        return value;
    }
}
