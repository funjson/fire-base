package dev.infinityknowledge.controlplane.config.ingestion;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** 验证语义切分不会因缺省或错误配置放大同步模型调用成本。 */
class SemanticChunkingPropertiesTest {

    @Test
    void usesConservativeDefaultsForUnspecifiedBudgets() {
        SemanticChunkingProperties properties = new SemanticChunkingProperties(
                false, 0, 0, 0, 0L, null
        );

        assertThat(properties.maximumEmbeddingInputs()).isEqualTo(128);
        assertThat(properties.maximumCandidates()).isEqualTo(127);
        assertThat(properties.maximumVectorValues()).isEqualTo(262_144L);
        assertThat(properties.stageTimeout()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void rejectsInvalidBudgetAndStageTimeout() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SemanticChunkingProperties(
                true, 1, 1, 1, 1L, Duration.ofSeconds(1)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new SemanticChunkingProperties(
                true, 2, 1, 1, 1L, Duration.ofSeconds(61)
        ));
    }
}
