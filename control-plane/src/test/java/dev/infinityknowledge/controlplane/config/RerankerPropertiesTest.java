package dev.infinityknowledge.controlplane.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankerPropertiesTest {

    @Test
    void appliesConservativeDefaults() {
        RerankerProperties properties = new RerankerProperties(
                false,
                0,
                0,
                0,
                0
        );

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.maxCandidates()).isEqualTo(24);
        assertThat(properties.maxQueryCharacters()).isEqualTo(4_096);
        assertThat(properties.maxCandidateCharacters()).isEqualTo(8_000);
        assertThat(properties.maxTotalCharacters()).isEqualTo(100_000);
    }

    @Test
    void rejectsAnUnboundedTotalCharacterBudget() {
        assertThatThrownBy(() -> new RerankerProperties(
                true,
                24,
                4_096,
                8_000,
                1_000_001
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTotalCharacters");
    }
}
