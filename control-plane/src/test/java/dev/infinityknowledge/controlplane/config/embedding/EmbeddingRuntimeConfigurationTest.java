package dev.infinityknowledge.controlplane.config.embedding;

import dev.infinityknowledge.controlplane.config.EmbeddingProperties;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证索引契约不依赖在线 Embedding 开关，避免默认配置下运行时无法启动。
 */
class EmbeddingRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddingRuntimeConfiguration.class)
            .withBean(EmbeddingProperties.class, properties(false, ""));

    @Test
    void keepsEmbeddingSpecWhenOnlineExecutionIsDisabled() {
        contextRunner
                .withPropertyValues("infinity.knowledge.embedding.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbeddingSpec.class);
                    assertThat(context).doesNotHaveBean(EmbeddingProvider.class);
                    assertThat(context).doesNotHaveBean("embeddingHttpClient");

                    EmbeddingSpec spec = context.getBean(EmbeddingSpec.class);
                    assertThat(spec.providerId()).isEqualTo("zhipu");
                    assertThat(spec.modelId()).isEqualTo("embedding-3");
                    assertThat(spec.dimensions()).isEqualTo(2_048);
                });
    }

    @Test
    void createsProviderOnlyWhenOnlineExecutionIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingRuntimeConfiguration.class)
                .withPropertyValues("infinity.knowledge.embedding.enabled=true")
                .withBean(EmbeddingProperties.class, properties(true, "test-api-key"))
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbeddingSpec.class);
                    assertThat(context).hasSingleBean(EmbeddingProvider.class);
                    assertThat(context).hasBean("embeddingHttpClient");
                });
    }

    private static Supplier<EmbeddingProperties> properties(
            boolean enabled,
            String apiKey
    ) {
        return () -> new EmbeddingProperties(
                enabled,
                "zhipu",
                "embedding-3",
                2_048,
                "v2",
                URI.create("https://example.invalid/embeddings"),
                apiKey,
                Duration.ofSeconds(1),
                8,
                1,
                Duration.ZERO,
                "",
                0
        );
    }
}
