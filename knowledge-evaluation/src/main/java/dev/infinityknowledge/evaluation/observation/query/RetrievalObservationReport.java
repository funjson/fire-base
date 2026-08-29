package dev.infinityknowledge.evaluation.observation.query;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.MetricDimensions;
import dev.infinityknowledge.evaluation.observation.MetricFact;
import dev.infinityknowledge.evaluation.observation.ObservationCompleteness;
import dev.infinityknowledge.evaluation.observation.ObservationIncompleteReason;
import dev.infinityknowledge.evaluation.observation.VisitedRetrievalConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 面向控制台下钻的安全检索观测只读模型。
 *
 * <p>事件只保留阶段、计数、耗时和稳定标识，不暴露原始 payload。指标维度继续使用
 * {@link MetricDimensions.Key} 的封闭集合，避免控制层重新引入任意高基数标签。</p>
 *
 * @param tenantId 报告所属租户，仅用于应用层二次隔离校验
 * @param requestId 调用方请求标识
 * @param executionId 最新一次实际执行标识
 * @param purpose 执行用途
 * @param completeness 事件完整性
 * @param incompleteReasons 不完整的稳定原因
 * @param missingSequences 已知范围内缺失的 sequence
 * @param visitedConfigurations 各次 Space visit 的有效配置身份
 * @param events 按 sequence 升序的安全阶段事实
 * @param metricFacts 按观测时间排序的版本化指标事实
 */
public record RetrievalObservationReport(
        TenantId tenantId,
        UUID requestId,
        UUID executionId,
        RetrievalObservationPurpose purpose,
        ObservationCompleteness completeness,
        Set<ObservationIncompleteReason> incompleteReasons,
        List<Long> missingSequences,
        List<VisitedRetrievalConfiguration> visitedConfigurations,
        List<EventFact> events,
        List<MetricFactView> metricFacts
) {

    /** 复制集合并验证只读报告内部身份和排序一致。 */
    public RetrievalObservationReport {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(completeness, "completeness must not be null");
        incompleteReasons = Set.copyOf(Objects.requireNonNull(
                incompleteReasons,
                "incompleteReasons must not be null"
        ));
        missingSequences = List.copyOf(Objects.requireNonNull(
                missingSequences,
                "missingSequences must not be null"
        ));
        visitedConfigurations = List.copyOf(Objects.requireNonNull(
                visitedConfigurations,
                "visitedConfigurations must not be null"
        ));
        events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
        metricFacts = List.copyOf(Objects.requireNonNull(
                metricFacts,
                "metricFacts must not be null"
        ));
        validateCompleteness(completeness, incompleteReasons, missingSequences);
        validateVisits(visitedConfigurations);
        validateEventsAndMetrics(events, metricFacts);
    }

    /**
     * 汇总报告中所有 Space，用于 HTTP 返回前按当前主体重新执行访问策略。
     *
     * <p>除事件 envelope 和 visit 外也包含指标 SPACE 维度，避免未来新增指标后出现
     * “页面可见指标、但主体无权访问该 Space”的旁路。</p>
     */
    public Set<KnowledgeSpaceId> involvedSpaceIds() {
        Set<KnowledgeSpaceId> involved = new LinkedHashSet<>();
        visitedConfigurations.stream()
                .map(VisitedRetrievalConfiguration::spaceId)
                .forEach(involved::add);
        events.stream().flatMap(event -> event.spaceIds().stream()).forEach(involved::add);
        metricFacts.stream()
                .map(MetricFactView::dimensions)
                .map(MetricDimensions::values)
                .map(values -> values.get(MetricDimensions.Key.SPACE))
                .filter(Objects::nonNull)
                .map(KnowledgeSpaceId::new)
                .forEach(involved::add);
        return Set.copyOf(involved);
    }

    /** 不含原始载荷的单阶段执行事实。 */
    public record EventFact(
            UUID eventId,
            long sequence,
            Set<KnowledgeSpaceId> spaceIds,
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
        /** 校验事件摘要没有伪造时间、计数或配置身份。 */
        public EventFact {
            Objects.requireNonNull(eventId, "eventId must not be null");
            if (sequence < 0L || sequence > RetrievalObservation.MAX_SEQUENCE) {
                throw new IllegalArgumentException("sequence is outside the supported range");
            }
            spaceIds = Set.copyOf(Objects.requireNonNull(
                    spaceIds,
                    "spaceIds must not be null"
            ));
            if (visitIndex < 0 || attemptIndex < 0) {
                throw new IllegalArgumentException(
                        "visitIndex and attemptIndex must not be negative"
                );
            }
            Objects.requireNonNull(stage, "stage must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            if (!RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT.equals(
                    configFingerprint
            ) && !RetrievalObservation.isResolvedConfigFingerprint(configFingerprint)) {
                throw new IllegalArgumentException("configFingerprint is invalid");
            }
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            Objects.requireNonNull(completedAt, "completedAt must not be null");
            if (completedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("completedAt must not precede startedAt");
            }
            long actualDuration = Duration.between(startedAt, completedAt).toMillis();
            if (durationMillis != actualDuration) {
                throw new IllegalArgumentException("durationMillis must match event timestamps");
            }
            if (inputCount < 0 || outputCount < 0) {
                throw new IllegalArgumentException("input/output counts must not be negative");
            }
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion must be positive");
            }
        }

        /** 从强类型事件提取不会泄露查询或正文的安全摘要。 */
        public static EventFact from(RetrievalObservation observation) {
            Objects.requireNonNull(observation, "observation must not be null");
            return new EventFact(
                    observation.eventId(),
                    observation.sequence(),
                    observation.spaceIds(),
                    observation.visitIndex(),
                    observation.attemptIndex(),
                    observation.stage(),
                    observation.status(),
                    observation.reasonCode(),
                    observation.configFingerprint(),
                    observation.startedAt(),
                    observation.completedAt(),
                    observation.duration().toMillis(),
                    observation.payload().inputCount(),
                    observation.payload().outputCount(),
                    observation.schemaVersion()
            );
        }
    }

    /** 指标事实类型；运行指标和依赖 Gold 的离线质量指标不会混淆。 */
    public enum MetricType {
        RUNTIME,
        OFFLINE_GOLD
    }

    /** Gold 数据集绑定；运行指标固定不携带该结构。 */
    public record GoldReference(
            UUID datasetId,
            long datasetVersion,
            UUID caseId
    ) {
        /** 校验完整 Gold 身份。 */
        public GoldReference {
            Objects.requireNonNull(datasetId, "datasetId must not be null");
            if (datasetVersion < 1L) {
                throw new IllegalArgumentException("datasetVersion must be positive");
            }
            Objects.requireNonNull(caseId, "caseId must not be null");
        }
    }

    /** 不含任意标签或模型原始输出的指标事实。 */
    public record MetricFactView(
            UUID sourceEventId,
            MetricType type,
            String metricKey,
            int metricDefinitionVersion,
            MetricFact.Aggregation aggregation,
            double value,
            MetricDimensions dimensions,
            Instant observedAt,
            GoldReference goldReference
    ) {
        /** 校验指标类型与 Gold 引用一致。 */
        public MetricFactView {
            Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(metricKey, "metricKey must not be null");
            if (metricDefinitionVersion < 1) {
                throw new IllegalArgumentException(
                        "metricDefinitionVersion must be positive"
                );
            }
            Objects.requireNonNull(aggregation, "aggregation must not be null");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("metric value must be finite");
            }
            Objects.requireNonNull(dimensions, "dimensions must not be null");
            Objects.requireNonNull(observedAt, "observedAt must not be null");
            if ((type == MetricType.RUNTIME) != (goldReference == null)) {
                throw new IllegalArgumentException(
                        "only OFFLINE_GOLD metric facts may contain a Gold reference"
                );
            }
        }

        /** 从内部指标事实投影为只读报告模型。 */
        public static MetricFactView from(MetricFact fact) {
            Objects.requireNonNull(fact, "fact must not be null");
            if (fact instanceof MetricFact.OfflineGold gold) {
                return new MetricFactView(
                        gold.sourceEventId(),
                        MetricType.OFFLINE_GOLD,
                        gold.metricKey(),
                        gold.metricDefinitionVersion(),
                        gold.aggregation(),
                        gold.value(),
                        gold.dimensions(),
                        gold.observedAt(),
                        new GoldReference(
                                gold.datasetId(),
                                gold.datasetVersion(),
                                gold.caseId()
                        )
                );
            }
            return new MetricFactView(
                    fact.sourceEventId(),
                    MetricType.RUNTIME,
                    fact.metricKey(),
                    fact.metricDefinitionVersion(),
                    fact.aggregation(),
                    fact.value(),
                    fact.dimensions(),
                    fact.observedAt(),
                    null
            );
        }
    }

    private static void validateCompleteness(
            ObservationCompleteness completeness,
            Set<ObservationIncompleteReason> reasons,
            List<Long> missingSequences
    ) {
        if ((completeness == ObservationCompleteness.COMPLETE) != reasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "COMPLETE must exactly match the absence of incomplete reasons"
            );
        }
        boolean hasSequenceGap = reasons.contains(ObservationIncompleteReason.SEQUENCE_GAP);
        if (hasSequenceGap != !missingSequences.isEmpty()) {
            throw new IllegalArgumentException(
                    "SEQUENCE_GAP must exactly match missingSequences"
            );
        }
    }

    private static void validateVisits(
            List<VisitedRetrievalConfiguration> visitedConfigurations
    ) {
        int previous = -1;
        for (VisitedRetrievalConfiguration configuration : visitedConfigurations) {
            if (configuration.visitIndex() <= previous) {
                throw new IllegalArgumentException(
                        "visitedConfigurations must be ordered by visitIndex"
                );
            }
            previous = configuration.visitIndex();
        }
    }

    private static void validateEventsAndMetrics(
            List<EventFact> events,
            List<MetricFactView> metrics
    ) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        long previousSequence = -1L;
        Set<UUID> eventIds = new HashSet<>();
        for (EventFact event : events) {
            if (event.sequence() <= previousSequence || !eventIds.add(event.eventId())) {
                throw new IllegalArgumentException(
                        "events must have unique identities and ascending sequences"
                );
            }
            previousSequence = event.sequence();
        }
        if (metrics.stream().map(MetricFactView::sourceEventId)
                .anyMatch(sourceEventId -> !eventIds.contains(sourceEventId))) {
            throw new IllegalArgumentException(
                    "metric facts must refer to an event contained in the report"
            );
        }
    }
}
