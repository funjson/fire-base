package dev.infinityknowledge.controlplane.config.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证串行 Tokenizer 与生成请求不能耗尽外层阶段超时。 */
class ModelStageBudgetValidatorTest {

    @Test
    void acceptsExternalCallBudgetWithSchedulingHeadroom() {
        assertThatCode(() -> ModelStageBudgetValidator.requireFits(
                Duration.ofSeconds(6),
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                "space router"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsBudgetEqualToOrGreaterThanStageTimeout() {
        assertThatThrownBy(() -> ModelStageBudgetValidator.requireFits(
                Duration.ofSeconds(4),
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                "feedback planner"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be less than stageTimeout");
    }

    @Test
    void rejectsNegativeAdapterLatencyDeclaration() {
        assertThatThrownBy(() -> ModelStageBudgetValidator.requireFits(
                Duration.ofSeconds(6),
                Duration.ofMillis(-1),
                Duration.ofSeconds(3),
                "space router"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("external latency budgets nonnegative");
    }
}
