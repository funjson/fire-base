package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.controlplane.observability.retrieval.RetrievalObservationSpringEventListener;
import dev.infinityknowledge.evaluation.observation.RetrievalObservationProcessor;
import dev.infinityknowledge.evaluation.observation.store.MetricFactStore;
import dev.infinityknowledge.evaluation.observation.store.RetrievalExecutionProjectionStore;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;
import dev.infinityknowledge.spi.retrieval.observation.RetrievalObservationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证观测消费存储可独立替换，且不会重复创建 Publisher。 */
class RetrievalObservationStorageConfigurationTest {

    @Test
    void assemblesDefaultPostgresConsumerWithoutAnotherPublisher() {
        new ApplicationContextRunner()
                .withUserConfiguration(RetrievalObservationStorageConfiguration.class)
                .withBean(
                        NamedParameterJdbcTemplate.class,
                        () -> mock(NamedParameterJdbcTemplate.class)
                )
                .withBean(
                        PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class)
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(RetrievalObservationEventStore.class);
                    assertThat(context).hasSingleBean(RetrievalExecutionProjectionStore.class);
                    assertThat(context).hasSingleBean(MetricFactStore.class);
                    assertThat(context).hasSingleBean(RetrievalObservationProcessor.class);
                    assertThat(context).hasSingleBean(
                            RetrievalObservationSpringEventListener.class
                    );
                    assertThat(context).doesNotHaveBean(RetrievalObservationPublisher.class);
                });
    }

    @Test
    void preservesReplacementStoragePorts() {
        RetrievalObservationEventStore eventStore = mock(
                RetrievalObservationEventStore.class
        );
        RetrievalExecutionProjectionStore projectionStore = mock(
                RetrievalExecutionProjectionStore.class
        );
        MetricFactStore metricFactStore = mock(MetricFactStore.class);

        new ApplicationContextRunner()
                .withUserConfiguration(RetrievalObservationStorageConfiguration.class)
                .withBean(RetrievalObservationEventStore.class, () -> eventStore)
                .withBean(RetrievalExecutionProjectionStore.class, () -> projectionStore)
                .withBean(MetricFactStore.class, () -> metricFactStore)
                .withBean(
                        NamedParameterJdbcTemplate.class,
                        () -> mock(NamedParameterJdbcTemplate.class)
                )
                .withBean(
                        PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class)
                )
                .run(context -> {
                    assertThat(context.getBean(RetrievalObservationEventStore.class))
                            .isSameAs(eventStore);
                    assertThat(context.getBean(RetrievalExecutionProjectionStore.class))
                            .isSameAs(projectionStore);
                    assertThat(context.getBean(MetricFactStore.class))
                            .isSameAs(metricFactStore);
                });
    }
}
