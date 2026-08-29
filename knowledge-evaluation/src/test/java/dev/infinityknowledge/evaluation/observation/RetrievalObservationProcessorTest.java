package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPayload;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationPurpose;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservationStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.observation.store.MetricFactStore;
import dev.infinityknowledge.evaluation.observation.store.RetrievalExecutionProjectionStore;
import dev.infinityknowledge.evaluation.observation.store.RetrievalObservationEventStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证处理器可从原始事件重放恢复，并对相同事件保持幂等。 */
class RetrievalObservationProcessorTest {
    private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("space-a");
    private static final String CONFIG = "a".repeat(64);

    @Test
    void rebuildsExecutionAfterProcessorRestartAndDoesNotDuplicateFacts() {
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingProjectionStore projections = new CapturingProjectionStore();
        CapturingMetricFactStore facts = new CapturingMetricFactStore();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        RetrievalObservation started = started(executionId, requestId);
        RetrievalObservation configured = configured(executionId, requestId);
        RetrievalObservation terminal = terminal(executionId, requestId);

        var firstProcess = new RetrievalObservationProcessor(events, projections, facts);
        firstProcess.process(started);
        firstProcess.process(configured);

        var restartedProcess = new RetrievalObservationProcessor(events, projections, facts);
        var completed = restartedProcess.process(terminal);
        var duplicate = restartedProcess.process(terminal);

        assertEquals(ObservationCompleteness.COMPLETE, completed.execution().completeness());
        assertEquals(
                List.of(new VisitedRetrievalConfiguration(0, SPACE, 1L, CONFIG)),
                completed.execution().visitedConfigurations()
        );
        assertEquals(16, facts.values.size());
        assertEquals(2, facts.executionSnapshot.size());
        assertEquals(1.0D, snapshotValue(facts, "retrieval.request.count"));
        assertEquals(1.0D, snapshotValue(facts, "retrieval.observation.complete"));
        assertEquals(4, facts.snapshotReplacementCount);
        assertTrue(duplicate.duplicate());
        assertTrue(duplicate.facts().isEmpty());
        assertEquals(completed.execution(), projections.latest);
    }

    @Test
    void incompleteExecutionStillContributesExactlyOneRequestAndCompletenessFact() {
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingMetricFactStore facts = new CapturingMetricFactStore();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var processor = new RetrievalObservationProcessor(
                events,
                new CapturingProjectionStore(),
                facts
        );
        var payload = new RetrievalObservationPayload.QueryAnalysisCompleted(
                new RetrievalObservationPayload.TextFingerprint(
                        "HMAC_SHA256", "key-v1", "c".repeat(64)
                ),
                Set.of(RetrievalChannel.KEYWORD),
                16,
                new RetrievalObservationPayload.ComponentVersion(
                        "QUERY_ANALYZER", "built-in", "none", "v1"
                )
        );

        var result = processor.process(observation(
                executionId,
                requestId,
                2L,
                CONFIG,
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                Set.of(SPACE),
                payload
        ));

        assertEquals(ObservationCompleteness.INCOMPLETE, result.execution().completeness());
        assertEquals(2, facts.executionSnapshot.size());
        assertEquals(1.0D, snapshotValue(facts, "retrieval.request.count"));
        assertEquals(0.0D, snapshotValue(facts, "retrieval.observation.complete"));
    }

    @Test
    void completeAndIncompleteExecutionsProduceTheCorrectSharedDenominator() {
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingMetricFactStore facts = new CapturingMetricFactStore();
        var processor = new RetrievalObservationProcessor(
                events,
                new CapturingProjectionStore(),
                facts
        );
        UUID completeExecution = UUID.randomUUID();
        UUID completeRequest = UUID.randomUUID();
        processor.process(started(completeExecution, completeRequest));
        processor.process(configured(completeExecution, completeRequest));
        processor.process(terminal(completeExecution, completeRequest));
        UUID incompleteExecution = UUID.randomUUID();
        processor.process(started(incompleteExecution, UUID.randomUUID()));

        assertEquals(2, facts.snapshotsByExecution.size());
        List<MetricFact.Runtime> allSnapshots = facts.snapshotsByExecution.values().stream()
                .flatMap(List::stream)
                .toList();
        assertEquals(2L, allSnapshots.stream()
                .filter(fact -> "retrieval.request.count".equals(fact.metricKey()))
                .count());
        assertEquals(2.0D, allSnapshots.stream()
                .filter(fact -> "retrieval.request.count".equals(fact.metricKey()))
                .mapToDouble(MetricFact.Runtime::value)
                .sum());
        assertEquals(0.5D, allSnapshots.stream()
                .filter(fact -> "retrieval.observation.complete".equals(fact.metricKey()))
                .mapToDouble(MetricFact.Runtime::value)
                .average()
                .orElseThrow());
    }

    @Test
    void onlyFinalTerminalCoverageConclusionContributesToPassRate() {
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingMetricFactStore facts = new CapturingMetricFactStore();
        UUID executionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var processor = new RetrievalObservationProcessor(
                events,
                new CapturingProjectionStore(),
                facts
        );
        processor.process(started(executionId, requestId));
        processor.process(configured(executionId, requestId));
        processor.process(coverage(
                executionId,
                requestId,
                2L,
                RetrievalObservationPayload.CoverageTerminalStatus.CONTINUE
        ));
        processor.process(coverage(
                executionId,
                requestId,
                3L,
                RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT
        ));
        processor.process(terminal(
                executionId,
                requestId,
                4L,
                RetrievalTerminalStatus.SUFFICIENT,
                RetrievalStopReason.SUFFICIENCY_THRESHOLD_REACHED
        ));

        assertFalse(facts.values.stream().anyMatch(
                fact -> "retrieval.coverage.sufficient".equals(fact.metricKey())
        ));
        assertEquals(3, facts.executionSnapshot.size());
        assertEquals(1.0D, snapshotValue(facts, "retrieval.coverage.sufficient"));
        assertEquals(1L, facts.executionSnapshot.stream()
                .filter(fact -> "retrieval.coverage.sufficient".equals(fact.metricKey()))
                .count());
    }

    @Test
    void finalInsufficientAddsDenominatorWhileNotEvaluatedIsExcluded() {
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingMetricFactStore facts = new CapturingMetricFactStore();
        var processor = new RetrievalObservationProcessor(
                events,
                new CapturingProjectionStore(),
                facts
        );
        UUID insufficientExecution = UUID.randomUUID();
        UUID insufficientRequest = UUID.randomUUID();
        processor.process(started(insufficientExecution, insufficientRequest));
        processor.process(configured(insufficientExecution, insufficientRequest));
        processor.process(coverage(
                insufficientExecution,
                insufficientRequest,
                2L,
                RetrievalObservationPayload.CoverageTerminalStatus.INSUFFICIENT
        ));
        processor.process(terminal(
                insufficientExecution,
                insufficientRequest,
                3L,
                RetrievalTerminalStatus.INSUFFICIENT,
                RetrievalStopReason.RETRIEVAL_BUDGET_EXHAUSTED
        ));
        UUID disabledExecution = UUID.randomUUID();
        UUID disabledRequest = UUID.randomUUID();
        processor.process(started(disabledExecution, disabledRequest));
        processor.process(configured(disabledExecution, disabledRequest));
        processor.process(terminal(disabledExecution, disabledRequest));

        List<MetricFact.Runtime> coverageConclusions = facts.snapshotsByExecution.values()
                .stream()
                .flatMap(List::stream)
                .filter(fact -> "retrieval.coverage.sufficient".equals(fact.metricKey()))
                .toList();
        assertEquals(1, coverageConclusions.size());
        assertEquals(0.0D, coverageConclusions.getFirst().value());
    }

    private static RetrievalObservation started(UUID executionId, UUID requestId) {
        var payload = new RetrievalObservationPayload.ExecutionStarted(
                new RetrievalObservationPayload.TextFingerprint(
                        "HMAC_SHA256", "key-v1", "b".repeat(64)
                ),
                8,
                1
        );
        return observation(
                executionId,
                requestId,
                0L,
                RetrievalObservation.UNRESOLVED_CONFIG_FINGERPRINT,
                RetrievalObservationStatus.STARTED,
                "NONE",
                Set.of(),
                payload
        );
    }

    private static RetrievalObservation configured(UUID executionId, UUID requestId) {
        return observation(
                executionId,
                requestId,
                1L,
                CONFIG,
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                Set.of(SPACE),
                new RetrievalObservationPayload.ConfigurationResolved(
                        SPACE, 1L, List.of(), List.of()
                )
        );
    }

    private static RetrievalObservation terminal(UUID executionId, UUID requestId) {
        return terminal(
                executionId,
                requestId,
                2L,
                RetrievalTerminalStatus.NOT_EVALUATED,
                RetrievalStopReason.COVERAGE_DISABLED
        );
    }

    private static RetrievalObservation terminal(
            UUID executionId,
            UUID requestId,
            long sequence,
            RetrievalTerminalStatus terminalStatus,
            RetrievalStopReason stopReason
    ) {
        return observation(
                executionId,
                requestId,
                sequence,
                CONFIG,
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                Set.of(SPACE),
                new RetrievalObservationPayload.ExecutionTerminal(
                        terminalStatus,
                        stopReason,
                        1,
                        0,
                        false,
                        List.of()
                )
        );
    }

    private static RetrievalObservation coverage(
            UUID executionId,
            UUID requestId,
            long sequence,
            RetrievalObservationPayload.CoverageTerminalStatus terminalStatus
    ) {
        boolean sufficient = terminalStatus
                == RetrievalObservationPayload.CoverageTerminalStatus.SUFFICIENT;
        return observation(
                executionId,
                requestId,
                sequence,
                CONFIG,
                RetrievalObservationStatus.SUCCEEDED,
                "NONE",
                Set.of(SPACE),
                new RetrievalObservationPayload.CoverageCheckCompleted(
                        new RetrievalObservationPayload.ComponentVersion(
                                "COVERAGE_JUDGE", "provider-a", "model-a", "v1"
                        ),
                        4,
                        4,
                        1,
                        RetrievalObservationPayload.OptionalCount.of(sufficient ? 1 : 0),
                        RetrievalObservationPayload.OptionalScore.of(
                                sufficient ? 0.8D : 0.4D
                        ),
                        RetrievalObservationPayload.OptionalScore.of(0.7D),
                        terminalStatus,
                        terminalStatus.name(),
                        RetrievalObservationPayload.UsageCount.none()
                )
        );
    }

    private static RetrievalObservation observation(
            UUID executionId,
            UUID requestId,
            long sequence,
            String config,
            RetrievalObservationStatus status,
            String reason,
            Set<KnowledgeSpaceId> spaceIds,
            RetrievalObservationPayload payload
    ) {
        return new RetrievalObservation(
                UUID.randomUUID(), executionId, requestId, sequence,
                RetrievalObservationPurpose.ONLINE, TENANT, spaceIds,
                0, 0, payload.stage(), status, reason, config,
                NOW, NOW, RetrievalObservation.CURRENT_SCHEMA_VERSION, payload
        );
    }

    private static final class InMemoryEventStore implements RetrievalObservationEventStore {
        private final Map<UUID, RetrievalObservation> byId = new HashMap<>();

        @Override
        public AppendOutcome append(RetrievalObservation observation) {
            RetrievalObservation existing = byId.get(observation.eventId());
            if (existing != null) {
                if (!existing.equals(observation)) {
                    throw new ObservationConflictException(
                            "eventId was reused with different content"
                    );
                }
                return AppendOutcome.ALREADY_PRESENT;
            }
            byId.values().stream()
                    .filter(event -> event.tenantId().equals(observation.tenantId()))
                    .filter(event -> event.executionId().equals(observation.executionId()))
                    .filter(event -> event.sequence() == observation.sequence())
                    .findFirst()
                    .ifPresent(ignored -> {
                        throw new ObservationConflictException(
                                "sequence was reused with different content"
                        );
                    });
            byId.put(observation.eventId(), observation);
            return AppendOutcome.APPENDED;
        }

        @Override
        public List<RetrievalObservation> events(TenantId tenantId, UUID executionId) {
            return byId.values().stream()
                    .filter(event -> event.tenantId().equals(tenantId))
                    .filter(event -> event.executionId().equals(executionId))
                    .sorted(Comparator.comparingLong(RetrievalObservation::sequence))
                    .toList();
        }
    }

    private static final class CapturingProjectionStore
            implements RetrievalExecutionProjectionStore {
        private RetrievalExecutionObservation latest;

        @Override
        public void upsert(RetrievalExecutionObservation execution) {
            latest = execution;
        }
    }

    private static final class CapturingMetricFactStore implements MetricFactStore {
        private final List<MetricFact> values = new ArrayList<>();
        private final Map<UUID, List<MetricFact.Runtime>> snapshotsByExecution =
                new HashMap<>();
        private List<MetricFact.Runtime> executionSnapshot = List.of();
        private int snapshotReplacementCount;

        @Override
        public AppendSummary appendAll(List<? extends MetricFact> facts) {
            values.addAll(facts);
            return new AppendSummary(facts.size(), 0);
        }

        @Override
        public void replaceExecutionSnapshot(List<MetricFact.Runtime> facts) {
            executionSnapshot = List.copyOf(facts);
            snapshotsByExecution.put(
                    executionSnapshot.getFirst().executionId(),
                    executionSnapshot
            );
            snapshotReplacementCount++;
        }
    }

    private static double snapshotValue(CapturingMetricFactStore store, String metricKey) {
        return store.executionSnapshot.stream()
                .filter(fact -> metricKey.equals(fact.metricKey()))
                .findFirst()
                .orElseThrow()
                .value();
    }
}
