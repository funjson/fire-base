package dev.infinityknowledge.evaluation;

import dev.infinityknowledge.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists tenant-scoped evaluation datasets, cases and runs.
 *
 * <p>The port follows the evaluation use case rather than exposing generic CRUD operations.
 * Database transactions and JSON representation remain responsibilities of the adapter.</p>
 */
public interface EvaluationStore {

    Dataset createDataset(
            TenantId tenantId,
            UUID id,
            String name,
            String description,
            Instant createdAt
    );

    List<Dataset> datasets(TenantId tenantId);

    Optional<Dataset> dataset(TenantId tenantId, UUID datasetId);

    Case createCase(TenantId tenantId, Case value);

    List<Case> cases(TenantId tenantId, UUID datasetId);

    Optional<Case> evaluationCase(TenantId tenantId, UUID caseId);

    Run createRun(TenantId tenantId, Run value);

    List<Run> runs(TenantId tenantId, UUID datasetId);

    Optional<Run> run(TenantId tenantId, UUID runId, boolean includeResults);

    void completeRun(
            TenantId tenantId,
            UUID runId,
            RetrievalEvaluationReport report,
            Instant completedAt
    );

    void failRun(TenantId tenantId, UUID runId, String errorCode, Instant completedAt);

    /** Stored dataset summary including aggregate case and run counts. */
    record Dataset(
            UUID id,
            String name,
            String description,
            long version,
            String status,
            long caseCount,
            long runCount,
            Instant createdAt
    ) {
        public Dataset {
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** Stored retrieval evaluation case. */
    record Case(
            UUID id,
            UUID datasetId,
            String query,
            Set<String> spaceIds,
            Set<UUID> expectedDocuments,
            Set<UUID> expectedChunks,
            int topK,
            Map<String, String> labels,
            Instant createdAt
    ) {
        public Case {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(datasetId, "datasetId must not be null");
            spaceIds = Set.copyOf(spaceIds);
            expectedDocuments = Set.copyOf(expectedDocuments);
            expectedChunks = Set.copyOf(expectedChunks);
            labels = Map.copyOf(labels);
        }
    }

    /** Stored asynchronous evaluation run. */
    record Run(
            UUID id,
            UUID datasetId,
            String status,
            int caseCount,
            int failedCaseCount,
            Map<String, Object> configuration,
            Map<String, Object> metrics,
            String requestedBy,
            String errorCode,
            Instant startedAt,
            Instant completedAt,
            List<CaseResult> results
    ) {
        public Run {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(datasetId, "datasetId must not be null");
            configuration = Map.copyOf(configuration);
            metrics = Map.copyOf(metrics);
            results = List.copyOf(results);
        }
    }

    /** Stored metrics and trace linkage for one evaluated case. */
    record CaseResult(
            UUID caseId,
            UUID traceId,
            String status,
            boolean hit,
            double recallAtK,
            double reciprocalRank,
            double ndcgAtK,
            int resultCount,
            long durationMs,
            String errorCode
    ) {
        public CaseResult {
            Objects.requireNonNull(caseId, "caseId must not be null");
        }
    }
}
