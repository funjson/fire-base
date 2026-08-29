package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.spi.retrieval.FeedbackQueryPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证固定 Chain 反馈查询生成器的条件装配和自定义实现后退规则。 */
class FeedbackPlannerConfigurationTest {
    private final FeedbackPlannerProperties properties = new FeedbackPlannerProperties(
            true,
            Duration.ofSeconds(4),
            URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions"),
            "test-key",
            "glm-test",
            Duration.ofSeconds(3),
            1,
            Duration.ZERO,
            24_000,
            512,
            "",
            0
    );
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeedbackPlannerConfiguration.class)
            .withBean(FeedbackPlannerProperties.class, () -> properties);

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
}
