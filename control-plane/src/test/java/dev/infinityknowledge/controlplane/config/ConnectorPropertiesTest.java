package dev.infinityknowledge.controlplane.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorPropertiesTest {

    @Test
    void normalizesCommaSeparatedDirectoryAllowlist() {
        ConnectorProperties properties = new ConnectorProperties(
                "C:\\knowledge, C:\\teams\\wiki",
                100,
                2_097_152
        );

        assertThat(properties.normalizedAllowedRoots())
                .containsExactly(
                        Path.of("C:\\knowledge").toAbsolutePath().normalize(),
                        Path.of("C:\\teams\\wiki").toAbsolutePath().normalize()
                );
    }

    @Test
    void rejectsUnboundedConnectorBudgets() {
        assertThatThrownBy(() -> new ConnectorProperties("", 0, 2_097_152))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectorProperties("", 100, 100_000_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
