package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.spi.model.ModelTokenEstimate;
import dev.infinityknowledge.spi.model.ModelTokenEstimateRequest;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import dev.infinityknowledge.spi.retrieval.CoverageJudge;
import dev.infinityknowledge.spi.retrieval.SpaceRouter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Space Router 与 Coverage Judge 都要求并接入同模型 Prompt 计数器。 */
class RetrievalModelConfigurationTest {

    @Test
    void installsSpaceRouterWithMatchingEstimator() {
        new ApplicationContextRunner()
                .withUserConfiguration(SpaceRouterConfiguration.class)
                .withBean(SpaceRouterProperties.class, this::spaceRouterProperties)
                .withBean(ModelTokenEstimator.class, RetrievalModelConfigurationTest::estimator)
                .withPropertyValues("infinity.knowledge.retrieval.space-router.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(SpaceRouter.class));
    }

    @Test
    void installsCoverageJudgeWithMatchingEstimator() {
        new ApplicationContextRunner()
                .withUserConfiguration(CoverageJudgeConfiguration.class)
                .withBean(CoverageJudgeProperties.class, this::coverageJudgeProperties)
                .withBean(ModelTokenEstimator.class, RetrievalModelConfigurationTest::estimator)
                .withPropertyValues("infinity.knowledge.retrieval.coverage-judge.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(CoverageJudge.class));
    }

    private SpaceRouterProperties spaceRouterProperties() {
        return new SpaceRouterProperties(
                true,
                Duration.ofSeconds(6),
                endpoint(),
                "test-key",
                "glm-5.2",
                Duration.ofSeconds(3),
                1,
                Duration.ZERO,
                32_000,
                8_192,
                512,
                "",
                0
        );
    }

    private CoverageJudgeProperties coverageJudgeProperties() {
        return new CoverageJudgeProperties(
                true,
                Duration.ofSeconds(10),
                endpoint(),
                "test-key",
                "glm-5.2",
                Duration.ofSeconds(6),
                1,
                Duration.ZERO,
                120_000,
                65_536,
                2_048,
                "",
                0
        );
    }

    private static ModelTokenEstimator estimator() {
        return new ModelTokenEstimator() {
            @Override
            public String providerId() {
                return "zhipu";
            }

            @Override
            public Set<String> supportedModelIds() {
                return Set.of("glm-5.2");
            }

            @Override
            public String version() {
                return "test-v1";
            }

            @Override
            public Duration maximumLatency() {
                return Duration.ofSeconds(1);
            }

            @Override
            public ModelTokenEstimate estimate(ModelTokenEstimateRequest request) {
                return new ModelTokenEstimate(1, true, version());
            }
        };
    }

    private static URI endpoint() {
        return URI.create("https://example.invalid/chat");
    }
}
