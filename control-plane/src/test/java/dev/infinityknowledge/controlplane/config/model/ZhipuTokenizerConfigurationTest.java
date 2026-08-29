package dev.infinityknowledge.controlplane.config.model;

import dev.infinityknowledge.ingestion.chunking.TokenCounter;
import dev.infinityknowledge.provider.zhipu.ZhipuModelTokenEstimator;
import dev.infinityknowledge.spi.model.ModelTokenEstimate;
import dev.infinityknowledge.spi.model.ModelTokenEstimateRequest;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证智谱 Prompt Tokenizer 的条件装配及其与 Chunk TokenCounter 的隔离。 */
class ZhipuTokenizerConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ZhipuTokenizerConfiguration.class);

    @Test
    void remainsDisabledByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(ModelTokenEstimator.class));
    }

    @Test
    void installsOneEstimatorForBothApprovedGlmModels() {
        contextRunner
                .withPropertyValues(
                        "infinity.knowledge.model-tokenizers.zhipu.enabled=true",
                        "infinity.knowledge.model-tokenizers.zhipu.api-key=test-key"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelTokenEstimator.class);
                    ModelTokenEstimator estimator = context.getBean(ModelTokenEstimator.class);
                    assertThat(estimator.supportedModelIds())
                            .containsExactlyInAnyOrder("glm-5.1", "glm-5.2");
                    assertThat(context).doesNotHaveBean(TokenCounter.class);
                });
    }

    @Test
    void appliesBoundedDefaultsWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "infinity.knowledge.model-tokenizers.zhipu.enabled=true",
                        "infinity.knowledge.model-tokenizers.zhipu.api-key=test-key"
                )
                .run(context -> {
                    ZhipuTokenizerProperties properties = context.getBean(
                            ZhipuTokenizerProperties.class
                    );
                    assertThat(properties.endpoint()).isEqualTo(URI.create(
                            "https://open.bigmodel.cn/api/paas/v4/tokenizer"
                    ));
                    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.maxAttempts()).isEqualTo(1);
                    assertThat(properties.initialBackoff()).isEqualTo(Duration.ZERO);
                    assertThat(properties.maximumInputCharacters()).isEqualTo(500_000);
                    assertThat(properties.proxyHost()).isEmpty();
                    assertThat(properties.proxyPort()).isZero();
                });
    }

    @Test
    void installsZhipuEstimatorAlongsideAnotherModelTokenEstimator() {
        contextRunner
                .withBean(
                        "otherModelTokenEstimator",
                        ModelTokenEstimator.class,
                        ZhipuTokenizerConfigurationTest::otherEstimator
                )
                .withPropertyValues(
                        "infinity.knowledge.model-tokenizers.zhipu.enabled=true",
                        "infinity.knowledge.model-tokenizers.zhipu.api-key=test-key"
                )
                .run(context -> {
                    assertThat(context.getBeansOfType(ModelTokenEstimator.class))
                            .containsKeys(
                                    "otherModelTokenEstimator",
                                    "zhipuModelTokenEstimator"
                            )
                            .hasSize(2);
                    assertThat(context.getBean("zhipuModelTokenEstimator"))
                            .isInstanceOf(ZhipuModelTokenEstimator.class);
                });
    }

    @Test
    void failsStartupWhenEnabledWithoutApiKey() {
        contextRunner
                .withPropertyValues(
                        "infinity.knowledge.model-tokenizers.zhipu.enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    private static ModelTokenEstimator otherEstimator() {
        return new ModelTokenEstimator() {
            @Override
            public String providerId() {
                return "other";
            }

            @Override
            public Set<String> supportedModelIds() {
                return Set.of("other-model");
            }

            @Override
            public String version() {
                return "other-v1";
            }

            @Override
            public Duration maximumLatency() {
                return Duration.ZERO;
            }

            @Override
            public ModelTokenEstimate estimate(ModelTokenEstimateRequest request) {
                return new ModelTokenEstimate(1, true, version());
            }
        };
    }
}
