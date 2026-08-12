package dev.infinityknowledge.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class)
            .withPropertyValues(
                    "infinity.knowledge.retrieval.mode=hybrid",
                    "infinity.knowledge.retrieval.candidate-multiplier=5",
                    "infinity.knowledge.retrieval.rrf-constant=60",
                    "infinity.knowledge.retrieval.sufficient-threshold=0.5",
                    "infinity.knowledge.retrieval.parallelism=4",
                    "infinity.knowledge.retrieval.queue-capacity=256",
                    "infinity.knowledge.retrieval.request-timeout=35s",
                    "infinity.knowledge.retrieval.channel-timeout=30s"
            );

    @Test
    void bindsThroughSpringBootConfigurationProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RetrievalProperties properties = context.getBean(RetrievalProperties.class);
            assertThat(properties.mode()).isEqualTo(RetrievalProperties.Mode.HYBRID);
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(35));
            assertThat(properties.channelTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void appliesBoundedTimeoutDefaults() {
        RetrievalProperties properties = new RetrievalProperties(
                RetrievalProperties.Mode.STANDARD,
                5,
                60,
                0.5D,
                4,
                256,
                null,
                null
        );

        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(35));
        assertThat(properties.channelTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsChannelTimeoutGreaterThanRequestTimeout() {
        assertThatThrownBy(() -> new RetrievalProperties(
                RetrievalProperties.Mode.STANDARD,
                5,
                60,
                0.5D,
                4,
                256,
                Duration.ofSeconds(5),
                Duration.ofSeconds(6)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelTimeout");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetrievalProperties.class)
    static class BindingConfiguration {
    }
}
