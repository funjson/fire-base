package dev.infinityknowledge.controlplane.application.evaluation;

import dev.infinityknowledge.controlplane.api.evaluation.EvaluationApi;
import dev.infinityknowledge.controlplane.application.common.WorkQueueSaturatedException;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.evaluation.EvaluationStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 面向管理控制台的租户级评测编排服务。
 *
 * <p>持久化细节由 {@link EvaluationStore} 负责；本服务只承担授权、运行提交和
 * API 映射，基准执行由 {@link EvaluationRunWorker} 完成。</p>
 */
@Service
public final class EvaluationApplicationService {
    private final EvaluationStore store;
    private final Clock clock;
    private final EvaluationRunCoordinator coordinator;

    public EvaluationApplicationService(
            EvaluationStore store,
            Clock clock,
            EvaluationRunCoordinator coordinator
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator must not be null"
        );
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
                "PENDING",
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
        store.createRun(
                principal.tenantId(),
                initial,
                principal,
                cases.stream().map(EvaluationStore.Case::id).toList()
        );
        if (!coordinator.submit(principal.tenantId(), runId)) {
            throw new WorkQueueSaturatedException(
                    "evaluation queue is saturated"
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

    /**
     * 对比两个不可变的已完成运行快照，不修改任一运行。
     * 质量门策略留在应用层，存储层保持通用。
     */
    public EvaluationApi.RunComparison compare(
            PrincipalContext principal,
            UUID datasetId,
            EvaluationApi.CompareRunsRequest request
    ) {
        requireAdmin(principal);
        requireDataset(principal, datasetId);
        EvaluationStore.Run baseline = completedRun(
                principal, datasetId, request.baselineRunId(), "baseline"
        );
        EvaluationStore.Run candidate = completedRun(
                principal, datasetId, request.candidateRunId(), "candidate"
        );
        if (baseline.id().equals(candidate.id())) {
            throw new IllegalArgumentException(
                    "baselineRunId and candidateRunId must be different"
            );
        }

        Map<String, Double> baselineMetrics = qualityMetrics(baseline);
        Map<String, Double> candidateMetrics = qualityMetrics(candidate);
        Map<String, Double> deltas = new LinkedHashMap<>();
        baselineMetrics.forEach((metric, value) ->
                deltas.put(metric, candidateMetrics.get(metric) - value)
        );
        double maximumRegression = request.maximumRegression() == null
                ? 0.0D : request.maximumRegression();
        List<EvaluationApi.GateViolation> violations = new ArrayList<>();
        addMinimumViolation(
                violations, "hitRate", request.minimumHitRate(), candidateMetrics
        );
        addMinimumViolation(
                violations, "recallAtK", request.minimumRecallAtK(), candidateMetrics
        );
        addMinimumViolation(
                violations, "mrr", request.minimumMrr(), candidateMetrics
        );
        addMinimumViolation(
                violations, "ndcgAtK", request.minimumNdcgAtK(), candidateMetrics
        );
        deltas.forEach((metric, delta) -> {
            if (delta < -maximumRegression) {
                violations.add(new EvaluationApi.GateViolation(
                        metric,
                        "MAXIMUM_REGRESSION",
                        -maximumRegression,
                        delta
                ));
            }
        });
        return new EvaluationApi.RunComparison(
                datasetId,
                baseline.id(),
                candidate.id(),
                baselineMetrics,
                candidateMetrics,
                deltas,
                maximumRegression,
                violations.isEmpty(),
                violations
        );
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

    private EvaluationStore.Run completedRun(
            PrincipalContext principal,
            UUID datasetId,
            UUID runId,
            String role
    ) {
        EvaluationStore.Run run = store.run(
                principal.tenantId(), runId, false
        ).orElseThrow(() -> new IllegalArgumentException(
                role + " evaluation run does not exist"
        ));
        if (!datasetId.equals(run.datasetId())) {
            throw new IllegalArgumentException(
                    role + " evaluation run does not belong to the dataset"
            );
        }
        if (!"SUCCEEDED".equals(run.status())) {
            throw new IllegalArgumentException(
                    role + " evaluation run must be SUCCEEDED"
            );
        }
        return run;
    }

    private static Map<String, Double> qualityMetrics(EvaluationStore.Run run) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (String name : List.of("hitRate", "recallAtK", "mrr", "ndcgAtK")) {
            Object value = run.metrics().get(name);
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException(
                        "evaluation run is missing numeric metric " + name
                );
            }
            double normalized = number.doubleValue();
            if (!Double.isFinite(normalized) || normalized < 0.0D || normalized > 1.0D) {
                throw new IllegalArgumentException(
                        "evaluation run contains invalid metric " + name
                );
            }
            metrics.put(name, normalized);
        }
        return Map.copyOf(metrics);
    }

    private static void addMinimumViolation(
            List<EvaluationApi.GateViolation> violations,
            String metric,
            Double minimum,
            Map<String, Double> candidateMetrics
    ) {
        if (minimum != null && candidateMetrics.get(metric) < minimum) {
            violations.add(new EvaluationApi.GateViolation(
                    metric,
                    "MINIMUM",
                    minimum,
                    candidateMetrics.get(metric)
            ));
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
