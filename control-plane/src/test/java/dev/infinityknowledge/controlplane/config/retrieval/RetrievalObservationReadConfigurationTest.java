package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.evaluation.observation.query.OnlineRetrievalObservabilityReader;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReportReader;
import dev.infinityknowledge.store.postgres.observation.PostgresOnlineRetrievalObservabilityReader;
import dev.infinityknowledge.store.postgres.observation.PostgresRetrievalObservationReportReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/** 验证默认 PostgreSQL 读适配器可被外部观测数据源替换。 */
class RetrievalObservationReadConfigurationTest {

    @Test
    void createsThePostgresReadAdapters() {
        var configuration = new RetrievalObservationReadConfiguration();
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

        assertInstanceOf(
                PostgresRetrievalObservationReportReader.class,
                configuration.retrievalObservationReportReader(jdbc)
        );
        assertInstanceOf(
                PostgresOnlineRetrievalObservabilityReader.class,
                configuration.onlineRetrievalObservabilityReader(jdbc)
        );
    }

    @Test
    void keepsExternallyProvidedReaders() {
        RetrievalObservationReportReader reportReplacement = mock(
                RetrievalObservationReportReader.class
        );
        OnlineRetrievalObservabilityReader onlineReplacement = mock(
                OnlineRetrievalObservabilityReader.class
        );

        new ApplicationContextRunner()
                .withUserConfiguration(RetrievalObservationReadConfiguration.class)
                .withBean(
                        RetrievalObservationReportReader.class,
                        () -> reportReplacement
                )
                .withBean(
                        OnlineRetrievalObservabilityReader.class,
                        () -> onlineReplacement
                )
                .run(context -> {
                    assertSame(
                            reportReplacement,
                            context.getBean(RetrievalObservationReportReader.class)
                    );
                    assertSame(
                            onlineReplacement,
                            context.getBean(OnlineRetrievalObservabilityReader.class)
                    );
                });
    }
}
