package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationCompleteness;
import dev.infinityknowledge.evaluation.observation.ObservationIncompleteReason;
import dev.infinityknowledge.evaluation.observation.query.RetrievalObservationReport;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 检索观测 HTTP 视图，仅公开阶段事实和有限指标维度。
 *
 * <p>该 DTO 不包含 payload、原始查询、查询指纹、候选正文或模型响应字段。</p>
 */
public record RetrievalObservationReportView(
        UUID requestId,
        UUID executionId,
        RetrievalObservationPurpose purpose,
        ObservationCompleteness completeness,
        List<ObservationIncompleteReason> incompleteReasons,
        List<Long> missingSequences,
        List<String> spaceIds,
        List<VisitedConfigurationView> visitedConfigurations,
        int eventCount,
        List<EventView> events,
        List<MetricView> metrics
) {

    /** 从已授权的安全读模型创建 HTTP 视图。 */
    public static RetrievalObservationReportView from(
            RetrievalObservationReport report
    ) {
        return new RetrievalObservationReportView(
                report.requestId(),
                report.executionId(),
                report.purpose(),
                report.completeness(),
                report.incompleteReasons().stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .toList(),
                report.missingSequences(),
                report.involvedSpaceIds().stream()
                        .map(spaceId -> spaceId.value())
                        .sorted()
                        .toList(),
                report.visitedConfigurations().stream()
                        .map(configuration -> new VisitedConfigurationView(
                                configuration.visitIndex(),
                                configuration.spaceId().value(),
                                configuration.sourceRevision(),
                                configuration.fingerprint()
                        ))
                        .toList(),
                report.events().size(),
                report.events().stream().map(EventView::from).toList(),
                report.metricFacts().stream().map(MetricView::from).toList()
        );
    }

    /** 一次 Space visit 的实际配置身份。 */
    public record VisitedConfigurationView(
            int visitIndex,
            String spaceId,
            long sourceRevision,
            String fingerprint
    ) {
    }

    /** 不含原始事件 payload 的阶段事实。 */
    public record EventView(
            UUID eventId,
            long sequence,
            List<String> spaceIds,
            int visitIndex,
            int attemptIndex,
            RetrievalObservationStage stage,
            RetrievalObservationStatus status,
            String reasonCode,
            String configFingerprint,
            Instant startedAt,
            Instant completedAt,
            long durationMillis,
            int inputCount,
            int outputCount,
            int schemaVersion
    ) {
        private static EventView from(RetrievalObservationReport.EventFact event) {
            return new EventView(
                    event.eventId(),
                    event.sequence(),
                    event.spaceIds().stream()
                            .map(spaceId -> spaceId.value())
                            .sorted()
                            .toList(),
                    event.visitIndex(),
                    event.attemptIndex(),
                    event.stage(),
                    event.status(),
                    event.reasonCode(),
                    event.configFingerprint(),
                    event.startedAt(),
                    event.completedAt(),
                    event.durationMillis(),
                    event.inputCount(),
                    event.outputCount(),
                    event.schemaVersion()
            );
        }
    }

    /** 显式字段化的有限指标维度，不接受任意标签名。 */
    public record MetricDimensionsView(
            String space,
            String queryCase,
            String config,
            String strategy,
            String attempt,
            String componentModel,
            String dataIndexVersion,
            String status,
            String stopReason,
            String purpose,
            String timeSlice
    ) {
        private static MetricDimensionsView from(MetricDimensions dimensions) {
            Map<MetricDimensions.Key, String> values = dimensions.values();
            return new MetricDimensionsView(
                    values.get(MetricDimensions.Key.SPACE),
                    values.get(MetricDimensions.Key.QUERY_CASE),
                    values.get(MetricDimensions.Key.CONFIG),
                    values.get(MetricDimensions.Key.STRATEGY),
                    values.get(MetricDimensions.Key.ATTEMPT),
                    values.get(MetricDimensions.Key.COMPONENT_MODEL),
                    values.get(MetricDimensions.Key.DATA_INDEX_VERSION),
                    values.get(MetricDimensions.Key.STATUS),
                    values.get(MetricDimensions.Key.STOP_REASON),
                    values.get(MetricDimensions.Key.PURPOSE),
                    values.get(MetricDimensions.Key.TIME_SLICE)
            );
        }
    }

    /** 离线质量指标绑定的 Gold 数据集身份。 */
    public record GoldReferenceView(
            UUID datasetId,
            long datasetVersion,
            UUID caseId
    ) {
        private static GoldReferenceView from(
                RetrievalObservationReport.GoldReference reference
        ) {
            return reference == null ? null : new GoldReferenceView(
                    reference.datasetId(),
                    reference.datasetVersion(),
                    reference.caseId()
            );
        }
    }

    /** 带版本、聚合口径和有限维度的指标事实。 */
    public record MetricView(
            UUID sourceEventId,
            RetrievalObservationReport.MetricType type,
            String metricKey,
            int metricDefinitionVersion,
            MetricFact.Aggregation aggregation,
            double value,
            MetricDimensionsView dimensions,
            Instant observedAt,
            GoldReferenceView goldReference
    ) {
        private static MetricView from(RetrievalObservationReport.MetricFactView fact) {
            return new MetricView(
                    fact.sourceEventId(),
                    fact.type(),
                    fact.metricKey(),
                    fact.metricDefinitionVersion(),
                    fact.aggregation(),
                    fact.value(),
                    MetricDimensionsView.from(fact.dimensions()),
                    fact.observedAt(),
                    GoldReferenceView.from(fact.goldReference())
            );
        }
    }
}
