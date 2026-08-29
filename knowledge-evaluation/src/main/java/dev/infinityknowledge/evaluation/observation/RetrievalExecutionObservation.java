package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 按 executionId 聚合并按 sequence 排序的一次检索观测快照。
 *
 * @param executionId 执行标识
 * @param requestId 请求关联标识
 * @param purpose 执行用途
 * @param tenantId 租户标识
 * @param visitedConfigurations 各 Space visit 实际采用的配置修订
 * @param events 当前已收到的有序事件
 * @param completeness 完整性结论
 * @param incompleteReasons 不完整原因
 * @param missingSequences 已知范围内缺失的 sequence
 */
public record RetrievalExecutionObservation(
        UUID executionId,
        UUID requestId,
        RetrievalObservationPurpose purpose,
        TenantId tenantId,
        List<VisitedRetrievalConfiguration> visitedConfigurations,
        List<RetrievalObservation> events,
        ObservationCompleteness completeness,
        Set<ObservationIncompleteReason> incompleteReasons,
        List<Long> missingSequences
) {
    /** 校验快照至少包含一条事件且完整性与原因一致。 */
    public RetrievalExecutionObservation {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        visitedConfigurations = List.copyOf(Objects.requireNonNull(
                visitedConfigurations,
                "visitedConfigurations must not be null"
        ));
        int previousVisitIndex = -1;
        for (VisitedRetrievalConfiguration configuration : visitedConfigurations) {
            if (configuration.visitIndex() <= previousVisitIndex) {
                throw new IllegalArgumentException(
                        "visitedConfigurations must be strictly ordered by visitIndex"
                );
            }
            previousVisitIndex = configuration.visitIndex();
        }
        events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        Objects.requireNonNull(completeness, "completeness must not be null");
        incompleteReasons = Set.copyOf(Objects.requireNonNull(
                incompleteReasons,
                "incompleteReasons must not be null"
        ));
        missingSequences = List.copyOf(Objects.requireNonNull(
                missingSequences,
                "missingSequences must not be null"
        ));
        boolean complete = completeness == ObservationCompleteness.COMPLETE;
        if (complete != incompleteReasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "COMPLETE must exactly match the absence of incomplete reasons"
            );
        }
        if (missingSequences.isEmpty()
                != !incompleteReasons.contains(ObservationIncompleteReason.SEQUENCE_GAP)) {
            throw new IllegalArgumentException(
                    "SEQUENCE_GAP must exactly match missingSequences"
            );
        }
    }

    /** 返回 sequence 为零的开始事件。 */
    public Optional<RetrievalObservation> startedEvent() {
        return events.stream().filter(event -> event.sequence() == 0L).findFirst();
    }

    /** 返回已收到的终态事件。 */
    public Optional<RetrievalObservation> terminalEvent() {
        return events.stream().filter(RetrievalObservation::terminal).findFirst();
    }
}
