package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReportReader;
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
    void createsThePostgresReadAdapter() {
        var configuration = new RetrievalObservationReadConfiguration();

        assertInstanceOf(
                PostgresRetrievalObservationReportReader.class,
                configuration.retrievalObservationReportReader(
                        mock(NamedParameterJdbcTemplate.class)
                )
        );
    }

    @Test
    void keepsAnExternallyProvidedReader() {
        RetrievalObservationReportReader replacement = mock(
                RetrievalObservationReportReader.class
        );

        new ApplicationContextRunner()
                .withUserConfiguration(RetrievalObservationReadConfiguration.class)
                .withBean(RetrievalObservationReportReader.class, () -> replacement)
                .run(context -> assertSame(
                        replacement,
                        context.getBean(RetrievalObservationReportReader.class)
                ));
    }
}
