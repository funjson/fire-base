package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.EvaluationApi;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.evaluation.EvaluationStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Tenant-scoped evaluation orchestration used by the management console.
 *
 * <p>Persistence details are delegated to {@link EvaluationStore}; this service owns
 * authorization, run submission and API mapping. Benchmark execution belongs to
 * {@link EvaluationRunWorker}.</p>
 */
@Service
public final class EvaluationApplicationService {
    private final EvaluationStore store;
    private final Clock clock;
    private final Executor executor;
    private final EvaluationRunWorker worker;

    public EvaluationApplicationService(
            EvaluationStore store,
            Clock clock,
            @Qualifier("evaluationExecutor") Executor executor,
            EvaluationRunWorker worker
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
    }

    public EvaluationApi.Dataset createDataset(
            PrincipalContext principal,
            EvaluationApi.CreateDatasetRequest request
    ) {
        requireAdmin(principal);
        Instant now = clock.instant();
        return toApi(store.createDataset(
                principal.tenantId(),
                UUID.randomUUID(),
                request.name().strip(),
                request.description() == null ? "" : request.description().strip(),
                now
        ));
    }

    public List<EvaluationApi.Dataset> datasets(PrincipalContext principal) {
        requireAdmin(principal);
        return store.datasets(principal.tenantId()).stream()
                .map(EvaluationApplicationService::toApi)
                .toList();
    }

    public EvaluationApi.Case createCase(
            PrincipalContext principal,
            UUID datasetId,
            EvaluationApi.CreateCaseRequest request
    ) {
        requireAdmin(principal);
        if (request.expectedDocuments().isEmpty() && request.expectedChunks().isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedDocuments or expectedChunks must contain at least one label"
            );
        }
        requireDataset(principal, datasetId);
        EvaluationStore.Case value = new EvaluationStore.Case(
                UUID.randomUUID(),
                datasetId,
                request.query().strip(),
                request.spaceIds(),
                request.expectedDocuments(),
                request.expectedChunks(),
                request.topK() == null ? 8 : request.topK(),
                request.labels(),
                clock.instant()
        );
        return toApi(store.createCase(principal.tenantId(), value));
    }

    public List<EvaluationApi.Case> cases(
            PrincipalContext principal,
            UUID datasetId
    ) {
        requireAdmin(principal);
        requireDataset(principal, datasetId);
        return store.cases(principal.tenantId(), datasetId).stream()
                .map(EvaluationApplicationService::toApi)
                .toList();
    }

    public EvaluationApi.Run start(
            PrincipalContext principal,
            UUID datasetId,
            EvaluationApi.StartRunRequest request
    ) {
        requireAdmin(principal);
        requireDataset(principal, datasetId);
        List<EvaluationStore.Case> cases = store.cases(principal.tenantId(), datasetId);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("evaluation dataset contains no cases");
        }

        UUID runId = UUID.randomUUID();
        int topK = request.topK() == null ? 8 : request.topK();
        Map<String, Object> configuration = new LinkedHashMap<>(request.configuration());
        configuration.put("topK", topK);
        configuration.put("runtime", "knowledge-gateway");
        EvaluationStore.Run initial = new EvaluationStore.Run(
                runId,
                datasetId,
                "RUNNING",
                cases.size(),
                0,
                configuration,
                Map.of(),
                principal.principalId().value(),
                null,
                clock.instant(),
                null,
                List.of()
        );
        store.createRun(principal.tenantId(), initial);
        try {
            executor.execute(() -> worker.execute(principal, runId, cases, topK));
        } catch (RejectedExecutionException saturated) {
            worker.fail(principal, runId, "EVALUATION_QUEUE_SATURATED");
            throw new WorkQueueSaturatedException(
                    "evaluation queue is saturated",
                    saturated
            );
        }
        return run(principal, runId, false);
    }

    public List<EvaluationApi.Run> runs(
            PrincipalContext principal,
            UUID datasetId
    ) {
        requireAdmin(principal);
        requireDataset(principal, datasetId);
        return store.runs(principal.tenantId(), datasetId).stream()
                .map(EvaluationApplicationService::toApi)
                .toList();
    }

    public EvaluationApi.Run run(
            PrincipalContext principal,
            UUID runId,
            boolean includeResults
    ) {
        requireAdmin(principal);
        EvaluationStore.Run value = store.run(
                principal.tenantId(), runId, includeResults
        ).orElseThrow(() -> new IllegalArgumentException(
                "evaluation run does not exist"
        ));
        return toApi(value);
    }

    private void requireDataset(PrincipalContext principal, UUID datasetId) {
        if (store.dataset(principal.tenantId(), datasetId).isEmpty()) {
            throw new IllegalArgumentException("evaluation dataset does not exist");
        }
    }

    private static EvaluationApi.Dataset toApi(EvaluationStore.Dataset value) {
        return new EvaluationApi.Dataset(
                value.id(), value.name(), value.description(), value.version(),
                value.status(), value.caseCount(), value.runCount(), value.createdAt()
        );
    }

    private static EvaluationApi.Case toApi(EvaluationStore.Case value) {
        return new EvaluationApi.Case(
                value.id(), value.datasetId(), value.query(), value.spaceIds(),
                value.expectedDocuments(), value.expectedChunks(), value.topK(),
                value.labels(), value.createdAt()
        );
    }

    private static EvaluationApi.Run toApi(EvaluationStore.Run value) {
        return new EvaluationApi.Run(
                value.id(), value.datasetId(), value.status(), value.caseCount(),
                value.failedCaseCount(), value.configuration(), value.metrics(),
                value.requestedBy(), value.errorCode(), value.startedAt(),
                value.completedAt(), value.results().stream()
                        .map(EvaluationApplicationService::toApi)
                        .toList()
        );
    }

    private static EvaluationApi.CaseResult toApi(EvaluationStore.CaseResult value) {
        return new EvaluationApi.CaseResult(
                value.caseId(), value.traceId(), value.status(), value.hit(),
                value.recallAtK(), value.reciprocalRank(), value.ndcgAtK(),
                value.resultCount(), value.durationMs(), value.errorCode()
        );
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
