package dev.infinityknowledge.controlplane.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankerPropertiesTest {

    @Test
    void appliesConservativeDefaults() {
        RerankerProperties properties = new RerankerProperties(
                false,
                null,
                null,
                0,
                0,
                0,
                0
        );

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.provider()).isEqualTo(RerankerProperties.Provider.EMBEDDING);
        assertThat(properties.stageTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.maxCandidates()).isEqualTo(24);
        assertThat(properties.maxQueryCharacters()).isEqualTo(4_096);
        assertThat(properties.maxCandidateCharacters()).isEqualTo(4_096);
        assertThat(properties.maxTotalCharacters()).isEqualTo(100_000);
    }

    @Test
    void rejectsAnUnboundedTotalCharacterBudget() {
        assertThatThrownBy(() -> new RerankerProperties(
                true,
                RerankerProperties.Provider.ZHIPU,
                Duration.ofSeconds(4),
                24,
                4_096,
                4_096,
                1_000_001
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTotalCharacters");
    }
}
