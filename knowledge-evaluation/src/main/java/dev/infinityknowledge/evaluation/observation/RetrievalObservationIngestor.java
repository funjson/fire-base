package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStage;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 纯 Java 的逐层检索事件摄取器。
 *
 * <p>该实现负责事件版本校验、eventId 幂等、execution 身份一致性和 sequence 完整性，
 * 不依赖 Spring、数据库或 MQ。进程内状态只用于当前阶段和单元测试；持久化适配器可复用
 * 相同的不变量与快照语义。</p>
 */
public final class RetrievalObservationIngestor {
    private final Set<Integer> supportedSchemaVersions;
    private final Map<EventKey, RetrievalObservation> eventsById = new HashMap<>();
    private final Map<UUID, MutableExecution> executions = new HashMap<>();

    /** 仅接受当前事件协议版本。 */
    public RetrievalObservationIngestor() {
        this(Set.of(RetrievalObservation.CURRENT_SCHEMA_VERSION));
    }

    /**
     * 创建显式版本集合的摄取器，便于未来 MQ 迁移期同时接受相邻版本。
     */
    public RetrievalObservationIngestor(Set<Integer> supportedSchemaVersions) {
        this.supportedSchemaVersions = Set.copyOf(Objects.requireNonNull(
                supportedSchemaVersions,
                "supportedSchemaVersions must not be null"
        ));
        if (this.supportedSchemaVersions.isEmpty()
                || this.supportedSchemaVersions.stream().anyMatch(version -> version < 1)) {
            throw new IllegalArgumentException(
                    "supportedSchemaVersions must contain positive versions"
            );
        }
    }

    /**
     * 摄取一条事件并返回当前 execution 快照；完全相同的 eventId 重投为幂等 no-op。
     */
    public synchronized IngestionResult ingest(RetrievalObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        if (!supportedSchemaVersions.contains(observation.schemaVersion())) {
            throw new IllegalArgumentException(
                    "unsupported retrieval observation schema version "
                            + observation.schemaVersion()
            );
        }
        EventKey eventKey = new EventKey(observation.tenantId(), observation.eventId());
        RetrievalObservation existingEvent = eventsById.get(eventKey);
        if (existingEvent != null) {
            if (!existingEvent.equals(observation)) {
                throw new ObservationConflictException(
                        "eventId was reused with different observation content"
                );
            }
            return new IngestionResult(true, requireExecution(observation.executionId()).snapshot());
        }

        MutableExecution execution = executions.get(observation.executionId());
        if (execution == null) {
            execution = new MutableExecution(observation);
            executions.put(observation.executionId(), execution);
        } else {
            execution.add(observation);
        }
        eventsById.put(eventKey, observation);
        return new IngestionResult(false, execution.snapshot());
    }

    /** 返回当前 execution 快照；未收到任何事件时返回空。 */
    public synchronized Optional<RetrievalExecutionObservation> execution(UUID executionId) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        MutableExecution value = executions.get(executionId);
        return value == null ? Optional.empty() : Optional.of(value.snapshot());
    }

    private MutableExecution requireExecution(UUID executionId) {
        MutableExecution execution = executions.get(executionId);
        if (execution == null) {
            throw new IllegalStateException("event index references a missing execution");
        }
        return execution;
    }

    /** 单条事件摄取的幂等结果。 */
    public record IngestionResult(
            boolean duplicate,
            RetrievalExecutionObservation execution
    ) {
        public IngestionResult {
            Objects.requireNonNull(execution, "execution must not be null");
        }
    }

    /** eventId 的幂等范围必须包含租户，避免不同租户互相制造冲突。 */
    private record EventKey(TenantId tenantId, UUID eventId) {
        private EventKey {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(eventId, "eventId must not be null");
        }
    }

    /** 单个 execution 的受锁保护可变组装状态。 */
    private static final class MutableExecution {
        private final UUID executionId;
        private final UUID requestId;
        private final RetrievalObservationPurpose purpose;
        private final TenantId tenantId;
        private final int schemaVersion;
        private final TreeMap<Long, RetrievalObservation> events = new TreeMap<>();
        private final Map<Integer, VisitFingerprint> fingerprintsByVisit = new HashMap<>();
        private final Map<Integer, VisitedRetrievalConfiguration> configurationsByVisit =
                new HashMap<>();
        private Long terminalSequence;
        private Integer terminalVisitIndex;

        private MutableExecution(RetrievalObservation first) {
            executionId = first.executionId();
            requestId = first.requestId();
            purpose = first.purpose();
            tenantId = first.tenantId();
            schemaVersion = first.schemaVersion();
            add(first);
        }

        private void add(RetrievalObservation observation) {
            validateIdentity(observation);
            RetrievalObservation sameSequence = events.get(observation.sequence());
            if (sameSequence != null) {
                throw new ObservationConflictException(
                        "sequence was reused by a different event"
                );
            }
            if (terminalSequence != null && observation.sequence() > terminalSequence) {
                throw new ObservationConflictException(
                        "event sequence must not exceed the terminal sequence"
                );
            }
            validateVisitOrder(observation);
            validateVisitConfiguration(observation);
            if (observation.terminal()) {
                if (terminalSequence != null) {
                    throw new ObservationConflictException(
                            "execution must contain exactly one terminal event"
                    );
                }
                Long sequenceAfterTerminal = events.higherKey(observation.sequence());
                if (sequenceAfterTerminal != null) {
                    throw new ObservationConflictException(
                            "terminal sequence must be greater than every layer sequence"
                    );
                }
                int greatestVisit = events.values().stream()
                        .mapToInt(RetrievalObservation::visitIndex)
                        .max()
                        .orElse(observation.visitIndex());
                if (greatestVisit > observation.visitIndex()) {
                    throw new ObservationConflictException(
                            "terminal event must belong to the last visit"
                    );
                }
            }
            applyVisitConfiguration(observation);
            if (RetrievalObservation.isResolvedConfigFingerprint(
                    observation.configFingerprint()
            )) {
                VisitFingerprint current = fingerprintsByVisit.get(observation.visitIndex());
                if (current == null || observation.sequence() < current.firstSequence()) {
                    fingerprintsByVisit.put(
                            observation.visitIndex(),
                            new VisitFingerprint(
                                    observation.configFingerprint(),
                                    observation.sequence()
                            )
                    );
                }
            }
            if (observation.terminal()) {
                terminalSequence = observation.sequence();
                terminalVisitIndex = observation.visitIndex();
            }
            events.put(observation.sequence(), observation);
        }

        private void validateIdentity(RetrievalObservation observation) {
            if (!executionId.equals(observation.executionId())
                    || !requestId.equals(observation.requestId())
                    || purpose != observation.purpose()
                    || !tenantId.equals(observation.tenantId())
                    || schemaVersion != observation.schemaVersion()) {
                throw new ObservationConflictException(
                        "execution identity changed"
                );
            }
            if (terminalVisitIndex != null
                    && observation.visitIndex() > terminalVisitIndex) {
                throw new ObservationConflictException(
                        "event visit must not exceed the terminal visit"
                );
            }
        }

        private void validateVisitOrder(RetrievalObservation observation) {
            boolean earlierVisitMovesBackward = events.headMap(
                            observation.sequence(),
                            false
                    ).values().stream()
                    .anyMatch(event -> event.visitIndex() > observation.visitIndex());
            boolean laterVisitMovesBackward = events.tailMap(
                            observation.sequence(),
                            false
                    ).values().stream()
                    .anyMatch(event -> event.visitIndex() < observation.visitIndex());
            if (earlierVisitMovesBackward || laterVisitMovesBackward) {
                throw new ObservationConflictException(
                        "visitIndex must not decrease as sequence increases"
                );
            }
        }

        private void validateVisitConfiguration(RetrievalObservation observation) {
            VisitFingerprint resolved = fingerprintsByVisit.get(observation.visitIndex());
            boolean incomingResolved = RetrievalObservation.isResolvedConfigFingerprint(
                    observation.configFingerprint()
            );
            if (resolved != null && incomingResolved
                    && !resolved.fingerprint().equals(observation.configFingerprint())) {
                throw new ObservationConflictException(
                        "resolved visit configuration changed"
                );
            }
            if (!incomingResolved && resolved != null
                    && observation.sequence() > resolved.firstSequence()) {
                throw new ObservationConflictException(
                        "visit config fingerprint cannot become unresolved after resolution"
                );
            }
            if (incomingResolved) {
                boolean laterUnresolved = events.tailMap(
                                observation.sequence(),
                                false
                        ).values().stream()
                        .filter(event -> event.visitIndex() == observation.visitIndex())
                        .anyMatch(event -> !RetrievalObservation.isResolvedConfigFingerprint(
                                event.configFingerprint()
                        ));
                if (laterUnresolved) {
                    throw new ObservationConflictException(
                            "visit config fingerprint cannot become unresolved after resolution"
                    );
                }
            }
            if (isSuccessfulConfiguration(observation)
                    && configurationsByVisit.containsKey(observation.visitIndex())) {
                throw new ObservationConflictException(
                        "visit must contain exactly one successful configuration event"
                );
            }
        }

        private void applyVisitConfiguration(RetrievalObservation observation) {
            if (!isSuccessfulConfiguration(observation)) {
                return;
            }
            var payload = (RetrievalObservationPayload.ConfigurationResolved) observation.payload();
            configurationsByVisit.put(
                    observation.visitIndex(),
                    new VisitedRetrievalConfiguration(
                            observation.visitIndex(),
                            payload.spaceId(),
                            payload.sourceRevision(),
                            observation.configFingerprint()
                    )
            );
        }

        private static boolean isSuccessfulConfiguration(RetrievalObservation observation) {
            return observation.stage() == RetrievalObservationStage.CONFIGURATION_RESOLVED
                    && (observation.status() == RetrievalObservationStatus.SUCCEEDED
                            || observation.status() == RetrievalObservationStatus.DEGRADED);
        }

        private RetrievalExecutionObservation snapshot() {
            EnumSet<ObservationIncompleteReason> reasons = EnumSet.noneOf(
                    ObservationIncompleteReason.class
            );
            RetrievalObservation started = events.get(0L);
            if (started == null
                    || started.stage() != RetrievalObservationStage.EXECUTION_STARTED) {
                reasons.add(ObservationIncompleteReason.STARTED_MISSING);
            }
            if (terminalSequence == null) {
                reasons.add(ObservationIncompleteReason.TERMINAL_MISSING);
            }

            long upperBound = terminalSequence == null
                    ? events.lastKey()
                    : terminalSequence;
            List<Long> missingSequences = new ArrayList<>();
            for (long sequence = 0L; sequence <= upperBound; sequence++) {
                if (!events.containsKey(sequence)) {
                    missingSequences.add(sequence);
                }
            }
            if (!missingSequences.isEmpty()) {
                reasons.add(ObservationIncompleteReason.SEQUENCE_GAP);
            }
            ObservationCompleteness completeness = reasons.isEmpty()
                    ? ObservationCompleteness.COMPLETE
                    : ObservationCompleteness.INCOMPLETE;
            List<RetrievalObservation> orderedEvents = events.values().stream()
                    .sorted(Comparator.comparingLong(RetrievalObservation::sequence))
                    .toList();
            return new RetrievalExecutionObservation(
                    executionId,
                    requestId,
                    purpose,
                    tenantId,
                    configurationsByVisit.values().stream()
                            .sorted(Comparator.comparingInt(
                                    VisitedRetrievalConfiguration::visitIndex
                            ))
                            .toList(),
                    orderedEvents,
                    completeness,
                    reasons,
                    missingSequences
            );
        }

        /** 一个 visit 第一次出现真实指纹的 sequence，用于支持 MQ 乱序补片。 */
        private record VisitFingerprint(String fingerprint, long firstSequence) {
        }
    }
}
