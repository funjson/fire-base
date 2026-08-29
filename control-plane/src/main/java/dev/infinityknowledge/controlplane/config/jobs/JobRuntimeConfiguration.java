package dev.infinityknowledge.controlplane.config.jobs;

import dev.infinityknowledge.controlplane.application.connector.ConnectorRunCoordinator;
import dev.infinityknowledge.controlplane.application.evaluation.EvaluationRunCoordinator;
import dev.infinityknowledge.controlplane.application.projection.ProjectionWorker;
import dev.infinityknowledge.jobs.KnowledgeJobsConfiguration;
import dev.infinityknowledge.jobs.ScheduledJobAction;
import dev.infinityknowledge.runtime.extraction.ExtractionRunProcessor;
import dev.infinityknowledge.runtime.ingestion.IngestionRunProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 把现有后台执行能力接到独立调度模块。
 *
 * <p>这里只做 Spring Bean 组装，不承载领取、重试或业务状态流转。</p>
 */
@Configuration(proxyBeanMethods = false)
@Import(KnowledgeJobsConfiguration.class)
public class JobRuntimeConfiguration {

    @Bean("connectorRecoveryJobAction")
    ScheduledJobAction connectorRecoveryJobAction(ConnectorRunCoordinator coordinator) {
        return coordinator::recover;
    }

    @Bean("evaluationRecoveryJobAction")
    ScheduledJobAction evaluationRecoveryJobAction(EvaluationRunCoordinator coordinator) {
        return coordinator::recover;
    }

    @Bean("projectionPollingJobAction")
    ScheduledJobAction projectionPollingJobAction(ProjectionWorker worker) {
        return worker::poll;
    }

    @Bean("extractionPollingJobAction")
    ScheduledJobAction extractionPollingJobAction(
            IngestionRunProcessor ingestionProcessor,
            ExtractionRunProcessor extractionProcessor
    ) {
        return () -> {
            // 正式摄取优先，但每轮仍至多处理一个正式任务和一个测试任务。
            ingestionProcessor.pollOnce();
            extractionProcessor.pollOnce();
        };
    }
}
