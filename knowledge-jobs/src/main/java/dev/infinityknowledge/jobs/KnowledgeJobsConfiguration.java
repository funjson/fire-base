package dev.infinityknowledge.jobs;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * 集中承载后台任务的 Spring 调度规则。
 *
 * <p>业务模块只提供一次性动作，本模块负责何时触发；这样 Controller/鉴权边界不会继续混入
 * {@link Scheduled}，也不会让纯业务运行时依赖 Spring 调度。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class KnowledgeJobsConfiguration {

    @Bean
    RecoveryJobsTrigger recoveryJobsTrigger(
            @Qualifier("connectorRecoveryJobAction") ScheduledJobAction connectorRecovery,
            @Qualifier("evaluationRecoveryJobAction") ScheduledJobAction evaluationRecovery
    ) {
        return new RecoveryJobsTrigger(connectorRecovery, evaluationRecovery);
    }

    @Bean
    ProjectionPollingTrigger projectionPollingTrigger(
            @Qualifier("projectionPollingJobAction") ScheduledJobAction projectionPolling
    ) {
        return new ProjectionPollingTrigger(projectionPolling);
    }

    @Bean
    ExtractionPollingTrigger extractionPollingTrigger(
            @Qualifier("extractionPollingJobAction") ScheduledJobAction extractionPolling
    ) {
        return new ExtractionPollingTrigger(extractionPolling);
    }

    static final class RecoveryJobsTrigger {
        private final ScheduledJobAction connectorRecovery;
        private final ScheduledJobAction evaluationRecovery;

        private RecoveryJobsTrigger(
                ScheduledJobAction connectorRecovery,
                ScheduledJobAction evaluationRecovery
        ) {
            this.connectorRecovery = Objects.requireNonNull(
                    connectorRecovery,
                    "connectorRecovery must not be null"
            );
            this.evaluationRecovery = Objects.requireNonNull(
                    evaluationRecovery,
                    "evaluationRecovery must not be null"
            );
        }

        /** 按原有频率恢复 Connector 与评测任务，单次处理量由业务侧限制。 */
        @Scheduled(
                fixedDelayString = "${infinity.knowledge.async.recovery-interval:30s}",
                initialDelayString = "${infinity.knowledge.async.initial-delay:10s}"
        )
        public void run() {
            connectorRecovery.execute();
            evaluationRecovery.execute();
        }
    }

    static final class ProjectionPollingTrigger {
        private final ScheduledJobAction projectionPolling;

        private ProjectionPollingTrigger(ScheduledJobAction projectionPolling) {
            this.projectionPolling = Objects.requireNonNull(
                    projectionPolling,
                    "projectionPolling must not be null"
            );
        }

        /** 按原有频率触发一次有界投影领取。 */
        @Scheduled(
                fixedDelayString = "${infinity.knowledge.indexing.poll-interval:1s}",
                initialDelayString = "${infinity.knowledge.indexing.poll-interval:1s}"
        )
        public void run() {
            projectionPolling.execute();
        }
    }

    static final class ExtractionPollingTrigger {
        private final ScheduledJobAction extractionPolling;

        private ExtractionPollingTrigger(ScheduledJobAction extractionPolling) {
            this.extractionPolling = Objects.requireNonNull(
                    extractionPolling,
                    "extractionPolling must not be null"
            );
        }

        /**
         * 触发一次有界的多文件任务轮询；正式摄取与抽取试验的处理顺序由装配层决定，
         * 任务状态和并发所有权由 Runtime 与 PostgreSQL 约束共同保证。
         */
        @Scheduled(
                fixedDelayString = "${infinity.knowledge.ingestion.extraction-runs.poll-interval:1s}",
                initialDelayString = "${infinity.knowledge.ingestion.extraction-runs.poll-interval:1s}"
        )
        public void run() {
            extractionPolling.execute();
        }
    }
}
