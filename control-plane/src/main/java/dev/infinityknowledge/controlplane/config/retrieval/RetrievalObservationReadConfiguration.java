package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReportReader;
import dev.infinityknowledge.store.postgres.observation.PostgresRetrievalObservationReportReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 组装检索观测的独立只读端口，控制层不直接依赖观测表或 SQL。 */
@Configuration
public class RetrievalObservationReadConfiguration {

    /** 注册默认 PostgreSQL 报告读取适配器；外部观测系统可替换同一端口。 */
    @Bean
    @ConditionalOnMissingBean(RetrievalObservationReportReader.class)
    RetrievalObservationReportReader retrievalObservationReportReader(
            NamedParameterJdbcTemplate jdbc
    ) {
        return new PostgresRetrievalObservationReportReader(jdbc);
    }
}
