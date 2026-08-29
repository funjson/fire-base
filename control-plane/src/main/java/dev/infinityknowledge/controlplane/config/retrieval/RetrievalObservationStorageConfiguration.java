package dev.infinityknowledge.controlplane.config.retrieval;

import dev.infinityknowledge.controlplane.observability.retrieval.RetrievalObservationSpringEventListener;
import dev.infinityknowledge.evaluation.observation.RetrievalObservationProcessor;
import dev.infinityknowledge.evaluation.observation.store.MetricFactStore;
import dev.infinityknowledge.evaluation.observation.store.RetrievalExecutionProjectionStore;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;
import dev.infinityknowledge.store.postgres.observation.PostgresMetricFactStore;
import dev.infinityknowledge.store.postgres.observation.PostgresRetrievalExecutionProjectionStore;
import dev.infinityknowledge.store.postgres.observation.PostgresRetrievalObservationEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 装配检索观测消费链和默认 PostgreSQL 存储。
 *
 * <p>三个存储端口均允许由外部 Bean 替换；Spring Event Publisher 由
 * {@link RetrievalObservationConfiguration} 唯一提供，本配置不会创建第二个发布器。</p>
 */
@Configuration
public class RetrievalObservationStorageConfiguration {

    /** 注册默认 PostgreSQL 原始事件存储。 */
    @Bean
    @ConditionalOnMissingBean(RetrievalObservationEventStore.class)
    RetrievalObservationEventStore retrievalObservationEventStore(
            NamedParameterJdbcTemplate jdbc
    ) {
        return new PostgresRetrievalObservationEventStore(jdbc);
    }

    /** 注册默认 PostgreSQL execution 投影存储。 */
    @Bean
    @ConditionalOnMissingBean(RetrievalExecutionProjectionStore.class)
    RetrievalExecutionProjectionStore retrievalExecutionProjectionStore(
            NamedParameterJdbcTemplate jdbc
    ) {
        return new PostgresRetrievalExecutionProjectionStore(jdbc);
    }

    /** 注册默认 PostgreSQL 指标事实存储。 */
    @Bean
    @ConditionalOnMissingBean(MetricFactStore.class)
    MetricFactStore retrievalMetricFactStore(NamedParameterJdbcTemplate jdbc) {
        return new PostgresMetricFactStore(jdbc);
    }

    /** 创建不依赖 Spring 的评测摄取与投影处理器。 */
    @Bean
    @ConditionalOnMissingBean(RetrievalObservationProcessor.class)
    RetrievalObservationProcessor retrievalObservationProcessor(
            RetrievalObservationEventStore eventStore,
            RetrievalExecutionProjectionStore projectionStore,
            MetricFactStore metricFactStore
    ) {
        return new RetrievalObservationProcessor(eventStore, projectionStore, metricFactStore);
    }

    /** 创建同步事务 Spring Event 消费者。 */
    @Bean
    @ConditionalOnMissingBean(RetrievalObservationSpringEventListener.class)
    RetrievalObservationSpringEventListener retrievalObservationSpringEventListener(
            RetrievalObservationProcessor processor,
            PlatformTransactionManager transactionManager
    ) {
        return new RetrievalObservationSpringEventListener(
                processor,
                new TransactionTemplate(transactionManager)
        );
    }
}
