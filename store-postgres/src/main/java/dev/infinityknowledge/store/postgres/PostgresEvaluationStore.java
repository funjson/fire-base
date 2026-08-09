package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.evaluation.EvaluationStore;
import dev.infinityknowledge.evaluation.RetrievalEvaluationReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL evaluation persistence adapter.
 */
public final class PostgresEvaluationStore implements EvaluationStore {
    private static final String DATASET_SELECT = """
            SELECT d.id, d.name, d.description, d.version, d.status, d.created_at,
                   count(DISTINCT c.id) AS case_count,
                   count(DISTINCT r.id) AS run_count
              FROM evaluation_dataset d
              LEFT JOIN evaluation_case c
                ON c.tenant_id = d.tenant_id AND c.dataset_id = d.id
              LEFT JOIN evaluation_run r
                ON r.tenant_id = d.tenant_id AND r.dataset_id = d.id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;

    public PostgresEvaluationStore(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    public Dataset createDataset(
            TenantId tenantId,
            UUID id,
            String name,
            String description,
            Instant createdAt
    ) {
        requireTenant(tenantId);
        return Objects.requireNonNull(transaction.execute(status -> {
            Long version = jdbc.queryForObject("""
                    SELECT coalesce(max(version), 0) + 1
                      FROM evaluation_dataset
                     WHERE tenant_id = ? AND name = ?
                    """, Long.class, tenantId.value(), name);
            long nextVersion = version == null ? 1L : version;
            jdbc.update("""
                    INSERT INTO evaluation_dataset
                        (tenant_id, id, name, description, version, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'DRAFT', ?)
                    """, tenantId.value(), id, name, description, nextVersion,
                    databaseTime(createdAt));
            return new Dataset(
                    id, name, description, nextVersion, "DRAFT", 0L, 0L, createdAt
            );
        }), "dataset creation must return a value");
    }

    @Override
    public List<Dataset> datasets(TenantId tenantId) {
        requireTenant(tenantId);
        return jdbc.query(DATASET_SELECT + """
                 WHERE d.tenant_id = ?
                 GROUP BY d.id, d.name, d.description, d.version, d.status, d.created_at
                 ORDER BY d.created_at DESC
                """, (row, number) -> dataset(row), tenantId.value());
    }

    @Override
    public Optional<Dataset> dataset(TenantId tenantId, UUID datasetId) {
        requireTenant(tenantId);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Dataset value = jdbc.query(DATASET_SELECT + """
                 WHERE d.tenant_id = ? AND d.id = ?
                 GROUP BY d.id, d.name, d.description, d.version, d.status, d.created_at
                """, row -> row.next() ? dataset(row) : null,
                tenantId.value(), datasetId);
        return Optional.ofNullable(value);
    }

    @Override
    public Case createCase(TenantId tenantId, Case value) {
        requireTenant(tenantId);
        Objects.requireNonNull(value, "value must not be null");
        jdbc.update("""
                INSERT INTO evaluation_case
                    (tenant_id, id, dataset_id, query_text, expected_documents_json,
                     expected_chunks_json, labels_json, space_ids_json, top_k, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                """,
                tenantId.value(),
                value.id(),
                value.datasetId(),
                value.query(),
                json(value.expectedDocuments()),
                json(value.expectedChunks()),
                json(value.labels()),
                json(value.spaceIds()),
                value.topK(),
                databaseTime(value.createdAt())
        );
        return value;
    }

    @Override
    public List<Case> cases(TenantId tenantId, UUID datasetId) {
        requireTenant(tenantId);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        return jdbc.query("""
                SELECT id, dataset_id, query_text, expected_documents_json::text,
                       expected_chunks_json::text, labels_json::text,
                       space_ids_json::text, top_k, created_at
                  FROM evaluation_case
                 WHERE tenant_id = ? AND dataset_id = ?
                 ORDER BY created_at, id
                """, (row, number) -> evaluationCase(row), tenantId.value(), datasetId);
    }

    @Override
    public Optional<Case> evaluationCase(TenantId tenantId, UUID caseId) {
        requireTenant(tenantId);
        Objects.requireNonNull(caseId, "caseId must not be null");
        Case value = jdbc.query("""
                SELECT id, dataset_id, query_text, expected_documents_json::text,
                       expected_chunks_json::text, labels_json::text,
                       space_ids_json::text, top_k, created_at
                  FROM evaluation_case
                 WHERE tenant_id = ? AND id = ?
                """, row -> row.next() ? evaluationCase(row) : null,
                tenantId.value(), caseId);
        return Optional.ofNullable(value);
    }

    @Override
    public Run createRun(TenantId tenantId, Run value) {
        requireTenant(tenantId);
        Objects.requireNonNull(value, "value must not be null");
        jdbc.update("""
                INSERT INTO evaluation_run
                    (tenant_id, id, dataset_id, generation_id, status,
                     configuration_json, metrics_json, requested_by,
                     case_count, failed_case_count, started_at)
                VALUES (?, ?, ?, NULL, 'RUNNING', ?::jsonb, NULL, ?, ?, 0, ?)
                """,
                tenantId.value(),
                value.id(),
                value.datasetId(),
                json(value.configuration()),
                value.requestedBy(),
                value.caseCount(),
                databaseTime(value.startedAt())
        );
        return value;
    }

    @Override
    public List<Run> runs(TenantId tenantId, UUID datasetId) {
        requireTenant(tenantId);
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        return jdbc.query("""
                SELECT id, dataset_id, status, case_count, failed_case_count,
                       configuration_json::text, metrics_json::text, requested_by,
                       error_code, started_at, completed_at
                  FROM evaluation_run
                 WHERE tenant_id = ? AND dataset_id = ?
                 ORDER BY started_at DESC
                """, (row, number) -> run(row, List.of()), tenantId.value(), datasetId);
    }

    @Override
    public Optional<Run> run(TenantId tenantId, UUID runId, boolean includeResults) {
        requireTenant(tenantId);
        Objects.requireNonNull(runId, "runId must not be null");
        Run value = jdbc.query("""
                SELECT id, dataset_id, status, case_count, failed_case_count,
                       configuration_json::text, metrics_json::text, requested_by,
                       error_code, started_at, completed_at
                  FROM evaluation_run
                 WHERE tenant_id = ? AND id = ?
                """, row -> row.next() ? run(row, List.of()) : null,
                tenantId.value(), runId);
        if (value == null || !includeResults) {
            return Optional.ofNullable(value);
        }
        List<CaseResult> results = jdbc.query("""
                SELECT case_id, trace_id, status, hit, recall_at_k,
                       reciprocal_rank, ndcg_at_k, result_count,
                       duration_ms, error_code
                  FROM evaluation_case_result
                 WHERE tenant_id = ? AND run_id = ?
                 ORDER BY case_id
                """, (row, number) -> new CaseResult(
                row.getObject("case_id", UUID.class),
                row.getObject("trace_id", UUID.class),
                row.getString("status"),
                row.getBoolean("hit"),
                row.getDouble("recall_at_k"),
                row.getDouble("reciprocal_rank"),
                row.getDouble("ndcg_at_k"),
                row.getInt("result_count"),
                row.getLong("duration_ms"),
                row.getString("error_code")
        ), tenantId.value(), runId);
        return Optional.of(new Run(
                value.id(), value.datasetId(), value.status(), value.caseCount(),
                value.failedCaseCount(), value.configuration(), value.metrics(),
                value.requestedBy(), value.errorCode(), value.startedAt(),
                value.completedAt(), results
        ));
    }

    @Override
    public void completeRun(
            TenantId tenantId,
            UUID runId,
            RetrievalEvaluationReport report,
            Instant completedAt
    ) {
        requireTenant(tenantId);
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        transaction.executeWithoutResult(status -> {
            for (var result : report.cases()) {
                jdbc.update("""
                        INSERT INTO evaluation_case_result
                            (tenant_id, run_id, case_id, trace_id, status, hit,
                             recall_at_k, reciprocal_rank, ndcg_at_k, result_count,
                             duration_ms, error_code, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, run_id, case_id) DO UPDATE
                        SET trace_id = EXCLUDED.trace_id,
                            status = EXCLUDED.status,
                            hit = EXCLUDED.hit,
                            recall_at_k = EXCLUDED.recall_at_k,
                            reciprocal_rank = EXCLUDED.reciprocal_rank,
                            ndcg_at_k = EXCLUDED.ndcg_at_k,
                            result_count = EXCLUDED.result_count,
                            duration_ms = EXCLUDED.duration_ms,
                            error_code = EXCLUDED.error_code
                        """,
                        tenantId.value(),
                        runId,
                        result.caseId(),
                        result.traceId(),
                        result.succeeded() ? "SUCCEEDED" : "FAILED",
                        result.hit(),
                        result.recallAtK(),
                        result.reciprocalRank(),
                        result.ndcgAtK(),
                        result.resultCount(),
                        result.durationMillis(),
                        result.errorCode(),
                        databaseTime(completedAt)
                );
            }
            Map<String, Object> metrics = Map.of(
                    "hitRate", report.hitRate(),
                    "recallAtK", report.recallAtK(),
                    "mrr", report.mrr(),
                    "ndcgAtK", report.ndcgAtK(),
                    "topK", report.topK()
            );
            int updated = jdbc.update("""
                    UPDATE evaluation_run
                       SET status = 'SUCCEEDED',
                           metrics_json = ?::jsonb,
                           case_count = ?,
                           failed_case_count = ?,
                           completed_at = ?
                     WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                    """,
                    json(metrics),
                    report.caseCount(),
                    report.failedCaseCount(),
                    databaseTime(completedAt),
                    tenantId.value(),
                    runId
            );
            if (updated != 1) {
                throw new IllegalStateException(
                        "evaluation run is no longer in RUNNING state"
                );
            }
        });
    }

    @Override
    public void failRun(
            TenantId tenantId,
            UUID runId,
            String errorCode,
            Instant completedAt
    ) {
        requireTenant(tenantId);
        jdbc.update("""
                UPDATE evaluation_run
                   SET status = 'FAILED', error_code = ?, completed_at = ?
                 WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                """, errorCode, databaseTime(completedAt), tenantId.value(), runId);
    }

    private Dataset dataset(ResultSet row) throws SQLException {
        return new Dataset(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("description"),
                row.getLong("version"),
                row.getString("status"),
                row.getLong("case_count"),
                row.getLong("run_count"),
                instant(row, "created_at")
        );
    }

    private Case evaluationCase(ResultSet row) throws SQLException {
        return new Case(
                row.getObject("id", UUID.class),
                row.getObject("dataset_id", UUID.class),
                row.getString("query_text"),
                stringSet(row.getString("space_ids_json")),
                uuidSet(row.getString("expected_documents_json")),
                uuidSet(row.getString("expected_chunks_json")),
                row.getInt("top_k"),
                stringMap(row.getString("labels_json")),
                instant(row, "created_at")
        );
    }

    private Run run(ResultSet row, List<CaseResult> results) throws SQLException {
        return new Run(
                row.getObject("id", UUID.class),
                row.getObject("dataset_id", UUID.class),
                row.getString("status"),
                row.getInt("case_count"),
                row.getInt("failed_case_count"),
                objectMap(row.getString("configuration_json")),
                objectMap(row.getString("metrics_json")),
                row.getString("requested_by"),
                row.getString("error_code"),
                instant(row, "started_at"),
                instant(row, "completed_at"),
                results
        );
    }

    private String json(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cannot serialize evaluation data", failure);
        }
    }

    private Set<String> stringSet(String json) {
        if (json == null) {
            return Set.of();
        }
        return Set.copyOf(read(json, new TypeReference<List<String>>() { }));
    }

    private Set<UUID> uuidSet(String json) {
        Set<UUID> values = new LinkedHashSet<>();
        for (String value : stringSet(json)) {
            values.add(UUID.fromString(value));
        }
        return Set.copyOf(values);
    }

    private Map<String, String> stringMap(String json) {
        if (json == null) {
            return Map.of();
        }
        return Map.copyOf(read(json, new TypeReference<Map<String, String>>() { }));
    }

    private Map<String, Object> objectMap(String json) {
        if (json == null) {
            return Map.of();
        }
        return Map.copyOf(read(json, new TypeReference<Map<String, Object>>() { }));
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return jsonMapper.readValue(json, type);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cannot read persisted evaluation JSON", failure);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static void requireTenant(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }
}
