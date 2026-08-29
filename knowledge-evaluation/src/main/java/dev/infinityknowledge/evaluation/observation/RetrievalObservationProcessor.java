package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.evaluation.observation.store.MetricFactStore;
import dev.infinityknowledge.evaluation.observation.store.RetrievalExecutionProjectionStore;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 将一条检索原始事件摄取为 execution 投影和运行指标事实。
 *
 * <p>该处理器不依赖 Spring、MQ 或数据库。事务由消息消费适配器提供；推荐把原始事件、
 * execution 投影、追加型逐事件事实和可替换执行快照置于同一事务。每次均从原始事件
 * 重放，进程重启后不会依赖丢失的内存聚合状态。</p>
 */
public final class RetrievalObservationProcessor {
    private final RetrievalObservationEventStore eventStore;
    private final RetrievalExecutionProjectionStore projectionStore;
    private final MetricFactStore metricFactStore;
    private final RuntimeMetricFactProjector metricProjector;

    /** 创建技术中立的观测处理器。 */
    public RetrievalObservationProcessor(
            RetrievalObservationEventStore eventStore,
            RetrievalExecutionProjectionStore projectionStore,
            MetricFactStore metricFactStore
    ) {
        this(
                eventStore,
                projectionStore,
                metricFactStore,
                new RuntimeMetricFactProjector()
        );
    }

    /** 创建可注入指标投影器的观测处理器。 */
    public RetrievalObservationProcessor(
            RetrievalObservationEventStore eventStore,
            RetrievalExecutionProjectionStore projectionStore,
            MetricFactStore metricFactStore,
            RuntimeMetricFactProjector metricProjector
    ) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
        this.projectionStore = Objects.requireNonNull(
                projectionStore,
                "projectionStore must not be null"
        );
        this.metricFactStore = Objects.requireNonNull(
                metricFactStore,
                "metricFactStore must not be null"
        );
        this.metricProjector = Objects.requireNonNull(
                metricProjector,
                "metricProjector must not be null"
        );
    }

    /**
     * 追加并处理一条事件。内容完全相同的重投只重建投影，不重复计算指标事实。
     */
    public ProcessingResult process(RetrievalObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        var appendOutcome = eventStore.append(observation);
        List<RetrievalObservation> events = eventStore.events(
                observation.tenantId(),
                observation.executionId()
        ).stream().sorted(Comparator.comparingLong(RetrievalObservation::sequence)).toList();
        if (events.stream().noneMatch(observation::equals)) {
            throw new IllegalStateException("appended observation is missing from event store");
        }

        RetrievalObservationIngestor ingestor = new RetrievalObservationIngestor();
        RetrievalExecutionObservation execution = null;
        for (RetrievalObservation event : events) {
            execution = ingestor.ingest(event).execution();
        }
        if (execution == null) {
            throw new IllegalStateException("execution event stream must not be empty");
        }
        projectionStore.upsert(execution);

        List<MetricFact.Runtime> facts = List.of();
        MetricFactStore.AppendSummary factSummary = new MetricFactStore.AppendSummary(0, 0);
        if (appendOutcome == RetrievalObservationEventStore.AppendOutcome.APPENDED) {
            facts = metricProjector.project(observation);
            factSummary = metricFactStore.appendAll(facts);
        }
        metricFactStore.replaceExecutionSnapshot(metricProjector.projectExecution(execution));
        return new ProcessingResult(
                appendOutcome == RetrievalObservationEventStore.AppendOutcome.ALREADY_PRESENT,
                execution,
                facts,
                factSummary
        );
    }

    /**
     * 一条事件处理后的稳定结果。
     *
     * @param facts 本次新事件产生的追加型事实；重复事件为空，不包含执行快照
     * @param factSummary 上述追加型事实的存储结果；执行快照由存储端原子替换
     */
    public record ProcessingResult(
            boolean duplicate,
            RetrievalExecutionObservation execution,
            List<MetricFact.Runtime> facts,
            MetricFactStore.AppendSummary factSummary
    ) {
        /** 复制处理结果，防止调用方修改事实列表。 */
        public ProcessingResult {
            Objects.requireNonNull(execution, "execution must not be null");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts must not be null"));
            Objects.requireNonNull(factSummary, "factSummary must not be null");
        }
    }
}
