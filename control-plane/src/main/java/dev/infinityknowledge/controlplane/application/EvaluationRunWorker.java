package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.config.AsyncRunProperties;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.evaluation.EvaluationStore;
import dev.infinityknowledge.evaluation.RetrievalEvaluationCase;
import dev.infinityknowledge.evaluation.RetrievalEvaluationRunner;
import dev.infinityknowledge.spi.KnowledgeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Executes one already-authorized evaluation run and persists its terminal state.
 */
@Component
public final class EvaluationRunWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            EvaluationRunWorker.class
    );

    private final EvaluationStore store;
    private final KnowledgeGateway gateway;
    private final AsyncRunProperties properties;
    private final Clock clock;

    public EvaluationRunWorker(
            EvaluationStore store,
            KnowledgeGateway gateway,
            AsyncRunProperties properties,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Runs every case with the supplied immutable principal snapshot.
     */
    public void execute(
            EvaluationStore.WorkLease lease,
            List<EvaluationStore.Case> cases
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        int topK = topK(lease.configuration());
        try {
            List<RetrievalEvaluationCase> benchmark = cases.stream()
                    .map(value -> new RetrievalEvaluationCase(
                            value.id(),
                            new KnowledgeQuery(
                                    UUID.randomUUID(),
                                    lease.principal(),
                                    value.query(),
                                    value.spaceIds().stream()
                                            .map(KnowledgeSpaceId::new)
                                            .collect(Collectors.toUnmodifiableSet()),
                                    topK,
                                    Map.of()
                            ),
                            value.expectedDocuments().stream()
                                    .map(DocumentId::new)
                                    .collect(Collectors.toUnmodifiableSet()),
                            value.expectedChunks()
                    ))
                    .toList();
            var report = new RetrievalEvaluationRunner(gateway).run(
                    benchmark,
                    topK,
                    () -> renew(lease)
            );
            if (!store.completeRun(lease, report, clock.instant())) {
                LOGGER.info(
                        "Evaluation completion was fenced: runId={}, tenantId={}",
                        lease.runId(), lease.tenantId().value()
                );
            }
        } catch (LeaseLostException lost) {
            LOGGER.info(
                    "Evaluation lease was lost: runId={}, tenantId={}",
                    lease.runId(), lease.tenantId().value()
            );
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Evaluation run failed: runId={}, tenantId={}, failureType={}",
                    lease.runId(),
                    lease.tenantId().value(),
                    failure.getClass().getSimpleName()
            );
            fail(lease, "EVALUATION_RUN_FAILED");
        }
    }

    /**
     * Persists a stable failure code when submission or execution cannot continue.
     */
    public void fail(
            EvaluationStore.WorkLease lease,
            String errorCode
    ) {
        if (!store.failRun(
                lease,
                errorCode,
                clock.instant()
        )) {
            LOGGER.info(
                    "Evaluation failure was fenced: runId={}, tenantId={}",
                    lease.runId(), lease.tenantId().value()
            );
        }
    }

    private void renew(EvaluationStore.WorkLease lease) {
        var now = clock.instant();
        if (!store.heartbeat(lease, now.plus(properties.leaseDuration()), now)) {
            throw new LeaseLostException();
        }
    }

    private static int topK(Map<String, Object> configuration) {
        Object value = configuration.getOrDefault("topK", 8);
        int topK = value instanceof Number number
                ? number.intValue()
                : Integer.parseInt(String.valueOf(value));
        if (topK < 1 || topK > 100) {
            throw new IllegalArgumentException("evaluation topK must be between 1 and 100");
        }
        return topK;
    }

    private static final class LeaseLostException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
