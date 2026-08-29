package dev.infinityknowledge.controlplane.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridRuntimeConfigurationTest {

    @Test
    void acceptsCompleteHybridConfiguration() {
        assertThatNoException().isThrownBy(() -> HybridRuntimeConfiguration.validate(
                retrieval(RetrievalProperties.Mode.HYBRID),
                vector(true),
                elasticsearch(true),
                embedding("test-key")
        ));
    }

    @Test
    void rejectsMissingMilvusChannel() {
        assertThatThrownBy(() -> HybridRuntimeConfiguration.validate(
                retrieval(RetrievalProperties.Mode.HYBRID),
                vector(false),
                elasticsearch(true),
                embedding("test-key")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector.enabled=true");
    }

    @Test
    void rejectsMissingElasticsearchChannel() {
        assertThatThrownBy(() -> HybridRuntimeConfiguration.validate(
                retrieval(RetrievalProperties.Mode.HYBRID),
                vector(true),
                elasticsearch(false),
                embedding("test-key")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elasticsearch.enabled=true");
    }

    @Test
    void rejectsMissingGlmCredential() {
        assertThatThrownBy(() -> HybridRuntimeConfiguration.validate(
                retrieval(RetrievalProperties.Mode.HYBRID),
                vector(true),
                elasticsearch(true),
                embedding("")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GLM embedding API key");
    }

    private static RetrievalProperties retrieval(RetrievalProperties.Mode mode) {
        return new RetrievalProperties(
                mode,
                5,
                60,
                0.5D,
                4,
                256,
                java.time.Duration.ofSeconds(35),
                java.time.Duration.ofSeconds(30)
        );
    }

    private static VectorProperties vector(boolean enabled) {
        return new VectorProperties(
                enabled,
                "http://localhost:19530",
                "",
                "knowledge_chunks",
                64,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30)
        );
    }

    private static ElasticsearchProperties elasticsearch(boolean enabled) {
        return new ElasticsearchProperties(
                enabled,
                "http://localhost:9200",
                "knowledge_chunks_v1",
                "",
                "",
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(15)
        );
    }

    private static EmbeddingProperties embedding(String apiKey) {
        return new EmbeddingProperties(
                true,
                "zhipu",
                "embedding-3",
                2_048,
                "v1",
                URI.create("https://open.bigmodel.cn/api/paas/v4/embeddings"),
                apiKey,
                Duration.ofSeconds(30),
                32,
                3,
                Duration.ofMillis(250),
                "",
                0
        );
    }
}
