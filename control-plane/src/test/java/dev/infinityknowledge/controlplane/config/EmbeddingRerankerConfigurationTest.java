package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.retrieval.Reranker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingRerankerConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddingRerankerConfiguration.class)
            .withBean(EmbeddingProvider.class, () -> (texts, spec) -> {
                throw new UnsupportedOperationException("must not be invoked while wiring");
            })
            .withBean(EmbeddingSpec.class, () -> new EmbeddingSpec("test", "model", 2))
            .withBean(RerankerProperties.class, () -> new RerankerProperties(
                    false,
                    RerankerProperties.Provider.EMBEDDING,
                    Duration.ofSeconds(4),
                    24,
                    4_096,
                    4_096,
                    100_000
            ));

    @Test
    void remainsDisabledByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(Reranker.class));
    }

    @Test
    void registersVendorNeutralRerankerWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "infinity.knowledge.retrieval.reranker.enabled=true",
                        "infinity.knowledge.retrieval.reranker.provider=embedding"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(Reranker.class));
    }

    @Test
    void failsClearlyWhenEnabledWithoutEmbeddingRuntime() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingRerankerConfiguration.class)
                .withBean(RerankerProperties.class, () -> new RerankerProperties(
                        true,
                        RerankerProperties.Provider.EMBEDDING,
                        Duration.ofSeconds(4),
                        24,
                        4_096,
                        4_096,
                        100_000
                ))
                .withPropertyValues(
                        "infinity.knowledge.retrieval.reranker.enabled=true",
                        "infinity.knowledge.retrieval.reranker.provider=embedding"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "Embedding reranker requires EmbeddingProvider; "
                                            + "enable infinity.knowledge.embedding or provide "
                                            + "a custom bean"
                            );
                });
    }
}
