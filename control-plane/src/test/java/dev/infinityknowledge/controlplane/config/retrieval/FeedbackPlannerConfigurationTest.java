package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.spi.model.ModelTokenEstimate;
import dev.infinityknowledge.spi.model.ModelTokenEstimator;
import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证固定 Chain 反馈查询生成器的条件装配和自定义实现后退规则。 */
class FeedbackPlannerConfigurationTest {
    private final FeedbackPlannerProperties properties = properties(Duration.ofSeconds(6));
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeedbackPlannerConfiguration.class)
            .withBean(FeedbackPlannerProperties.class, () -> properties)
            .withBean(ModelTokenEstimator.class, FeedbackPlannerConfigurationTest::estimator);

    @Test
    void remainsInactiveByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(FeedbackQueryPlanner.class));
    }

    @Test
    void registersFeedbackPlannerWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "infinity.knowledge.retrieval.feedback-planner.enabled=true"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(FeedbackQueryPlanner.class));
    }

    @Test
    void refusesModelPlannerWithoutExactPromptTokenEstimator() {
        new ApplicationContextRunner()
                .withUserConfiguration(FeedbackPlannerConfiguration.class)
                .withBean(FeedbackPlannerProperties.class, () -> properties)
                .withPropertyValues(
                        "infinity.knowledge.retrieval.feedback-planner.enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesLatencyBudgetThatWouldExhaustTheStageTimeout() {
        new ApplicationContextRunner()
                .withUserConfiguration(FeedbackPlannerConfiguration.class)
                .withBean(
                        FeedbackPlannerProperties.class,
                        () -> properties(Duration.ofSeconds(4))
                )
                .withBean(
                        ModelTokenEstimator.class,
                        FeedbackPlannerConfigurationTest::estimator
                )
                .withPropertyValues(
                        "infinity.knowledge.retrieval.feedback-planner.enabled=true"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void keepsCallerProvidedFeedbackPlannerInsteadOfCreatingAnotherBean() {
        FeedbackQueryPlanner custom = FeedbackQueryPlanner.unavailable();
        contextRunner
                .withBean(FeedbackQueryPlanner.class, () -> custom)
                .withPropertyValues(
                        "infinity.knowledge.retrieval.feedback-planner.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(FeedbackQueryPlanner.class);
                    assertThat(context.getBean(FeedbackQueryPlanner.class)).isSameAs(custom);
                });
    }

    private static ModelTokenEstimator estimator() {
        return new ModelTokenEstimator() {
            @Override
            public String providerId() {
                return "zhipu";
            }

            @Override
            public Set<String> supportedModelIds() {
                return Set.of("glm-test");
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
            public ModelTokenEstimate estimate(
                    dev.infinityknowledge.spi.model.ModelTokenEstimateRequest request
            ) {
                return new ModelTokenEstimate(1, true, version());
            }
        };
    }

    private static FeedbackPlannerProperties properties(Duration stageTimeout) {
        return new FeedbackPlannerProperties(
                true,
                stageTimeout,
                URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions"),
                "test-key",
                "glm-test",
                Duration.ofSeconds(3),
                1,
                Duration.ZERO,
                24_000,
                16_384,
                512,
                "",
                0
        );
    }
}
