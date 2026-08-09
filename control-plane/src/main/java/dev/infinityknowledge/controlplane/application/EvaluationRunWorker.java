package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
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
    private final Clock clock;

    public EvaluationRunWorker(
            EvaluationStore store,
            KnowledgeGateway gateway,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Runs every case with the supplied immutable principal snapshot.
     */
    public void execute(
            PrincipalContext principal,
            UUID runId,
            List<EvaluationStore.Case> cases,
            int topK
    ) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        try {
            List<RetrievalEvaluationCase> benchmark = cases.stream()
                    .map(value -> new RetrievalEvaluationCase(
                            value.id(),
                            new KnowledgeQuery(
                                    UUID.randomUUID(),
                                    principal,
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
            var report = new RetrievalEvaluationRunner(gateway).run(benchmark, topK);
            store.completeRun(principal.tenantId(), runId, report, clock.instant());
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Evaluation run failed: runId={}, tenantId={}",
                    runId,
                    principal.tenantId().value(),
                    failure
            );
            fail(principal, runId, "EVALUATION_RUN_FAILED");
        }
    }

    /**
     * Persists a stable failure code when submission or execution cannot continue.
     */
    public void fail(
            PrincipalContext principal,
            UUID runId,
            String errorCode
    ) {
        store.failRun(
                principal.tenantId(),
                runId,
                errorCode,
                clock.instant()
        );
    }
}
