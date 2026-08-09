package dev.infinityknowledge.controlplane.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Requests and views for retrieval evaluation management.
 */
public final class EvaluationApi {

    private EvaluationApi() {
    }

    public record CreateDatasetRequest(
            @NotBlank @Size(max = 256) String name,
            @Size(max = 4_000) String description
    ) {
    }

    public record CreateCaseRequest(
            @NotBlank @Size(max = 16_000) String query,
            @NotEmpty Set<@NotBlank String> spaceIds,
            Set<UUID> expectedDocuments,
            Set<UUID> expectedChunks,
            @Min(1) @Max(100) Integer topK,
            Map<String, String> labels
    ) {
        public CreateCaseRequest {
            spaceIds = spaceIds == null ? Set.of() : Set.copyOf(spaceIds);
            expectedDocuments = expectedDocuments == null
                    ? Set.of() : Set.copyOf(expectedDocuments);
            expectedChunks = expectedChunks == null ? Set.of() : Set.copyOf(expectedChunks);
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
    }

    public record StartRunRequest(
            @Min(1) @Max(100) Integer topK,
            Map<String, String> configuration
    ) {
        public StartRunRequest {
            configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        }
    }

    public record Dataset(
            UUID id,
            String name,
            String description,
            long version,
            String status,
            long caseCount,
            long runCount,
            Instant createdAt
    ) {
    }

    public record Case(
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
            spaceIds = Set.copyOf(spaceIds);
            expectedDocuments = Set.copyOf(expectedDocuments);
            expectedChunks = Set.copyOf(expectedChunks);
            labels = Map.copyOf(labels);
        }
    }

    public record Run(
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
            configuration = Map.copyOf(configuration);
            metrics = Map.copyOf(metrics);
            results = List.copyOf(results);
        }
    }

    public record CaseResult(
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
    }
}
