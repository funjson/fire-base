package dev.infinityknowledge.controlplane.config.retrieval;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证三个在线检索模型阶段共享 GLM-5.2 默认值并为 Tokenizer 预留超时。 */
class RetrievalModelPropertiesTest {

    @Test
    void usesGlm52AndHeadroomAwareStageTimeoutsByDefault() {
        SpaceRouterProperties router = new SpaceRouterProperties(
                false, null, null, null, null, null, 0, null,
                0, 0, 0, null, 0
        );
        FeedbackPlannerProperties planner = new FeedbackPlannerProperties(
                false, null, null, null, null, null, 0, null,
                0, 0, 0, null, 0
        );
        CoverageJudgeProperties judge = new CoverageJudgeProperties(
                false, null, null, null, null, null, 0, null,
                0, 0, 0, null, 0
        );

        assertThat(router.model()).isEqualTo("glm-5.2");
        assertThat(router.stageTimeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(router.maximumPromptTokens()).isEqualTo(8_192);
        assertThat(planner.model()).isEqualTo("glm-5.2");
        assertThat(planner.stageTimeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(planner.maximumPromptTokens()).isEqualTo(16_384);
        assertThat(judge.model()).isEqualTo("glm-5.2");
        assertThat(judge.stageTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(judge.maximumPromptTokens()).isEqualTo(65_536);
    }
}
