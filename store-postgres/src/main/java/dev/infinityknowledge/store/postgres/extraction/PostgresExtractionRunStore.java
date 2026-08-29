package dev.infinityknowledge.store.postgres.extraction;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.extraction.ActiveExtractionRunException;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionPreview;
import dev.infinityknowledge.spi.extraction.ExtractionPublicationInProgressException;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ChunkDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.CleaningDiagnostics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 PostgreSQL 保存 TEST_ONLY 与 INGEST 共用的多文件抽取任务。
 *
 * <p>任务领取只更新 {@code worker_id} 与 {@code heartbeat_at}。所有结束写入都再次
 * 匹配当前 Worker，因此超时接管后，旧 Worker 无法覆盖新执行结果。</p>
 */
public final class PostgresExtractionRunStore implements ExtractionRunStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;

    /** 创建事务型任务存储。 */
    public PostgresExtractionRunStore(
            NamedParameterJdbcTemplate jdbc,
            TransactionTemplate transaction
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    public RunSnapshot begin(BeginRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            int inserted = jdbc.update("""
                    INSERT INTO extraction_run
                        (tenant_id, id, space_id, mode, status, language,
                         config_version, config_fingerprint, config_snapshot_json,
                         dataset_id, baseline_run_id, gate_status,
                         created_by, created_at)
                    SELECT :tenantId, :runId, s.id, :mode, 'QUEUED', :language,
                           :configVersion, :configFingerprint,
                           CAST(:configSnapshot AS jsonb),
                           :datasetId, :baselineRunId, 'NOT_EVALUATED',
                           :createdBy, :createdAt
                      FROM knowledge_space s
                     WHERE s.tenant_id = :tenantId
                       AND s.id = :spaceId
                       AND s.status = 'ACTIVE'
                       AND (
                           CAST(:baselineRunId AS uuid) IS NULL
                           OR EXISTS (
                               SELECT 1
                                 FROM extraction_run baseline
                                WHERE baseline.tenant_id = :tenantId
                                  AND baseline.space_id = :spaceId
                                  AND baseline.id = :baselineRunId
                                  AND baseline.status = 'SUCCEEDED'
                           )
                       )
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", request.tenantId().value())
                    .addValue("runId", request.runId())
                    .addValue("spaceId", request.spaceId().value())
                    .addValue("mode", request.mode().name())
                    .addValue("language", request.language())
                    .addValue("configVersion", request.configVersion())
                    .addValue("configFingerprint", request.configSnapshot().fingerprint())
                    .addValue("configSnapshot", configSnapshotJson(request.configSnapshot()))
                    .addValue("datasetId", request.datasetId())
                    .addValue("baselineRunId", request.baselineRunId())
                    .addValue("createdBy", request.createdBy().value())
                    .addValue("createdAt", databaseTime(request.createdAt())));
            if (inserted != 1) {
                throw new IllegalArgumentException("knowledge space does not exist or is not active");
            }
        } catch (DuplicateKeyException conflict) {
            throw new ActiveExtractionRunException();
        }
        return snapshotById(request.runId()).orElseThrow();
    }

    @Override
    public void appendSource(
            UUID runId,
            SourceAsset sourceAsset,
            PublicationAttributes publication,
            Instant now
    ) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(sourceAsset, "sourceAsset must not be null");
        Objects.requireNonNull(publication, "publication must not be null");
        Objects.requireNonNull(now, "now must not be null");
        transaction.executeWithoutResult(ignored -> {
            MapSqlParameterSource parameters = sourceParameters(runId, sourceAsset, now);
            List<UUID> accepting = jdbc.queryForList("""
                    SELECT id
                      FROM extraction_run
                     WHERE id = :runId
                       AND tenant_id = :tenantId
                       AND space_id = :spaceId
                       AND status = 'QUEUED'
                       AND ready_at IS NULL
                     FOR UPDATE
                    """, parameters, UUID.class);
            if (accepting.isEmpty()) {
                throw new IllegalStateException("extraction run no longer accepts sources");
            }
            jdbc.update("""
                    INSERT INTO source_asset
                        (tenant_id, id, space_id, object_id, storage_id,
                         original_file_name, media_type, content_length,
                         checksum_sha256, stored_at, created_at)
                    VALUES (:tenantId, :assetId, :spaceId, :objectId, :storageId,
                           :fileName, :mediaType, :contentLength,
                           :checksum, :storedAt, :createdAt)
                    """, parameters);
            jdbc.update("""
                    INSERT INTO extraction_run_item
                        (tenant_id, id, run_id, source_asset_id,
                         external_id, title, authority,
                         status, stage, created_at)
                    VALUES
                        (:tenantId, :itemId, :runId, :assetId,
                         :externalId, :title, :authority,
                         'QUEUED', 'STORED', :createdAt)
                    """, parameters
                    .addValue("itemId", UUID.randomUUID())
                    .addValue("externalId", publication.externalId())
                    .addValue("title", publication.title())
                    .addValue("authority", publication.authority()));
        });
    }

    @Override
    public RunSnapshot seal(UUID runId, int expectedItems, Instant now) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (expectedItems < 1) {
            throw new IllegalArgumentException("expectedItems must be positive");
        }
        int updated = jdbc.update("""
                UPDATE extraction_run run
                   SET ready_at = :now
                 WHERE run.id = :runId
                   AND run.status = 'QUEUED'
                   AND run.ready_at IS NULL
                   AND :expectedItems = (
                       SELECT count(*)
                         FROM extraction_run_item item
                        WHERE item.tenant_id = run.tenant_id
                          AND item.run_id = run.id
                   )
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("expectedItems", expectedItems)
                .addValue("now", databaseTime(now)));
        if (updated != 1) {
            throw new IllegalStateException("extraction run cannot be sealed");
        }
        return snapshotById(runId).orElseThrow();
    }

    @Override
    public void failCreation(UUID runId, String errorCode, Instant now) {
        transaction.executeWithoutResult(ignored -> {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("runId", runId)
                    .addValue("errorCode", requiredCode(errorCode))
                    .addValue("now", databaseTime(now));
            int updated = jdbc.update("""
                    UPDATE extraction_run
                       SET status = 'FAILED', error_code = :errorCode,
                           finished_at = :now, worker_id = NULL, heartbeat_at = NULL
                     WHERE id = :runId
                       AND status = 'QUEUED'
                       AND ready_at IS NULL
                    """, parameters);
            if (updated == 1) {
                jdbc.update("""
                        UPDATE extraction_run_item
                           SET status = 'FAILED', error_code = :errorCode,
                               finished_at = :now
                         WHERE run_id = :runId
                           AND status IN ('QUEUED', 'RUNNING')
                        """, parameters);
            }
        });
    }

    @Override
    public Optional<RunSnapshot> find(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            UUID runId
    ) {
        return snapshots("""
                WHERE run.tenant_id = :tenantId
                  AND run.space_id = :spaceId
                  AND run.id = :runId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("spaceId", spaceId.value())
                .addValue("runId", runId), "").stream().findFirst();
    }

    @Override
    public List<RunSnapshot> list(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            int limit
    ) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return snapshots("""
                WHERE run.tenant_id = :tenantId
                  AND run.space_id = :spaceId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("spaceId", spaceId.value())
                .addValue("limit", limit), "LIMIT :limit");
    }

    @Override
    public Optional<RunSnapshot> claimNext(
            ExtractionMode mode,
            String workerId,
            Instant staleBefore,
            Instant now
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("mode", Objects.requireNonNull(mode, "mode must not be null").name())
                .addValue("workerId", requiredWorkerId(workerId))
                .addValue("staleBefore", databaseTime(staleBefore))
                .addValue("now", databaseTime(now));
        List<UUID> claimed = jdbc.queryForList("""
                WITH candidate AS (
                    SELECT id
                      FROM extraction_run
                     WHERE ready_at IS NOT NULL
                       AND mode = :mode
                       AND (
                           status = 'QUEUED'
                           OR (
                               status IN ('RUNNING', 'CANCEL_REQUESTED')
                               AND heartbeat_at < :staleBefore
                           )
                       )
                     ORDER BY created_at, id
                     LIMIT 1
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE extraction_run run
                   SET status = CASE
                                    WHEN run.status = 'CANCEL_REQUESTED'
                                        THEN 'CANCEL_REQUESTED'
                                    ELSE 'RUNNING'
                                END,
                       worker_id = :workerId,
                       heartbeat_at = :now,
                       started_at = coalesce(run.started_at, :now),
                       error_code = NULL
                  FROM candidate
                 WHERE run.id = candidate.id
                RETURNING run.id
                """, parameters, UUID.class);
        return claimed.stream().findFirst().flatMap(this::snapshotById);
    }

    @Override
    public int failInterruptedUploads(Instant createdBefore, Instant now, int limit) {
        Objects.requireNonNull(createdBefore, "createdBefore must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Integer failed = transaction.execute(ignored -> {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("createdBefore", databaseTime(createdBefore))
                    .addValue("now", databaseTime(now))
                    .addValue("limit", limit);
            List<UUID> runIds = jdbc.queryForList("""
                    WITH candidate AS (
                        SELECT id
                          FROM extraction_run
                         WHERE status = 'QUEUED'
                           AND ready_at IS NULL
                           AND created_at < :createdBefore
                         ORDER BY created_at, id
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED
                    )
                    UPDATE extraction_run run
                       SET status = 'FAILED', error_code = 'UPLOAD_INTERRUPTED',
                           finished_at = :now
                      FROM candidate
                     WHERE run.id = candidate.id
                    RETURNING run.id
                    """, parameters, UUID.class);
            if (!runIds.isEmpty()) {
                jdbc.update("""
                        UPDATE extraction_run_item
                           SET status = 'FAILED', error_code = 'UPLOAD_INTERRUPTED',
                               finished_at = :now
                         WHERE run_id IN (:runIds)
                           AND status IN ('QUEUED', 'RUNNING')
                        """, new MapSqlParameterSource()
                        .addValue("runIds", runIds)
                        .addValue("now", databaseTime(now)));
            }
            return runIds.size();
        });
        return failed == null ? 0 : failed;
    }

    @Override
    public boolean heartbeat(UUID runId, String workerId, Instant now) {
        return jdbc.update("""
                UPDATE extraction_run
                   SET heartbeat_at = :now
                 WHERE id = :runId
                   AND status = 'RUNNING'
                   AND worker_id = :workerId
                """, ownership(runId, workerId).addValue("now", databaseTime(now))) == 1;
    }

    @Override
    public boolean cancellationRequested(UUID runId, String workerId) {
        Boolean requested = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM extraction_run
                     WHERE id = :runId
                       AND status = 'CANCEL_REQUESTED'
                       AND worker_id = :workerId
                )
                """, ownership(runId, workerId), Boolean.class);
        return Boolean.TRUE.equals(requested);
    }

    @Override
    public boolean startItem(UUID runId, UUID itemId, String workerId, Instant now) {
        return jdbc.update("""
                UPDATE extraction_run_item item
                   SET status = 'RUNNING', stage = 'PARSE',
                       error_code = NULL,
                       started_at = coalesce(item.started_at, :now),
                       finished_at = NULL
                 WHERE item.id = :itemId
                   AND item.run_id = :runId
                   AND item.status IN ('QUEUED', 'RUNNING')
                   AND item.stage <> 'PUBLISHING'
                   AND EXISTS (
                       SELECT 1
                         FROM extraction_run run
                        WHERE run.id = item.run_id
                          AND run.tenant_id = item.tenant_id
                          AND run.status = 'RUNNING'
                          AND run.worker_id = :workerId
                   )
                """, ownership(runId, workerId)
                .addValue("itemId", itemId)
                .addValue("now", databaseTime(now))) == 1;
    }

    @Override
    public boolean beginPublication(
            UUID runId,
            UUID itemId,
            String workerId,
            Instant now
    ) {
        return jdbc.update("""
                WITH owned_run AS (
                    UPDATE extraction_run
                       SET heartbeat_at = :now
                     WHERE id = :runId
                       AND mode = 'INGEST'
                       AND status = 'RUNNING'
                       AND worker_id = :workerId
                    RETURNING id, tenant_id
                )
                UPDATE extraction_run_item item
                   SET stage = 'PUBLISHING'
                  FROM owned_run run
                 WHERE item.id = :itemId
                   AND item.run_id = run.id
                   AND item.tenant_id = run.tenant_id
                   AND item.status = 'RUNNING'
                   AND item.stage <> 'PUBLISHING'
                """, ownership(runId, workerId)
                .addValue("itemId", itemId)
                .addValue("now", databaseTime(now))) == 1;
    }

    @Override
    public boolean succeedItem(
            UUID runId,
            UUID itemId,
            String workerId,
            ItemDiagnostics diagnostics,
            ExtractionPreview preview,
            Instant now
    ) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        return jdbc.update("""
                UPDATE extraction_run_item item
                   SET status = 'SUCCEEDED', stage = 'COMPLETED', error_code = NULL,
                       parser_id = :parserId,
                       processor_version = :processorVersion,
                       element_count = :elementCount,
                       chunk_count = :chunkCount,
                       parse_duration_ms = :parseDuration,
                       clean_duration_ms = :cleanDuration,
                       chunk_duration_ms = :chunkDuration,
                       diagnostics_json = CAST(:diagnostics AS jsonb),
                       preview_json = CAST(:preview AS jsonb),
                       preview_truncated = :previewTruncated,
                       finished_at = :now
                 WHERE item.id = :itemId
                   AND item.run_id = :runId
                   AND item.status = 'RUNNING'
                   AND EXISTS (
                       SELECT 1
                         FROM extraction_run run
                        WHERE run.id = item.run_id
                          AND run.tenant_id = item.tenant_id
                          AND run.mode = 'TEST_ONLY'
                          AND run.status = 'RUNNING'
                          AND run.worker_id = :workerId
                   )
                """, successParameters(
                runId, itemId, workerId, diagnostics, preview, now
        )) == 1;
    }

    @Override
    public boolean succeedPublishedItem(
            UUID runId,
            UUID itemId,
            String workerId,
            ItemDiagnostics diagnostics,
            ExtractionPreview preview,
            DocumentId documentId,
            UUID revisionId,
            Instant now
    ) {
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        return jdbc.update("""
                UPDATE extraction_run_item item
                   SET status = 'SUCCEEDED', stage = 'COMPLETED', error_code = NULL,
                       parser_id = :parserId,
                       processor_version = :processorVersion,
                       element_count = :elementCount,
                       chunk_count = :chunkCount,
                       parse_duration_ms = :parseDuration,
                       clean_duration_ms = :cleanDuration,
                       chunk_duration_ms = :chunkDuration,
                       diagnostics_json = CAST(:diagnostics AS jsonb),
                       preview_json = CAST(:preview AS jsonb),
                       preview_truncated = :previewTruncated,
                       document_id = :documentId,
                       revision_id = :revisionId,
                       finished_at = :now
                 WHERE item.id = :itemId
                   AND item.run_id = :runId
                   AND item.status = 'RUNNING'
                   AND item.stage = 'PUBLISHING'
                   AND EXISTS (
                       SELECT 1
                         FROM extraction_run run
                        WHERE run.id = item.run_id
                          AND run.tenant_id = item.tenant_id
                          AND run.mode = 'INGEST'
                          AND run.status = 'RUNNING'
                          AND run.worker_id = :workerId
                   )
                """, successParameters(
                runId, itemId, workerId, diagnostics, preview, now
        ).addValue("documentId", documentId.value())
                .addValue("revisionId", revisionId)) == 1;
    }

    @Override
    public boolean skipDuplicateItem(
            UUID runId,
            UUID itemId,
            String workerId,
            DocumentId documentId,
            UUID revisionId,
            Instant now
    ) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        return jdbc.update("""
                UPDATE extraction_run_item item
                   SET status = 'SKIPPED_DUPLICATE', stage = 'COMPLETED',
                       error_code = NULL, document_id = :documentId,
                       revision_id = :revisionId, finished_at = :now
                 WHERE item.id = :itemId
                   AND item.run_id = :runId
                   AND item.status = 'RUNNING'
                   AND item.stage IN ('PARSE', 'PUBLISHING')
                   AND EXISTS (
                       SELECT 1
                         FROM extraction_run run
                        WHERE run.id = item.run_id
                          AND run.tenant_id = item.tenant_id
                          AND run.mode = 'INGEST'
                          AND run.status = 'RUNNING'
                          AND run.worker_id = :workerId
                   )
                """, ownership(runId, workerId)
                .addValue("itemId", itemId)
                .addValue("documentId", documentId.value())
                .addValue("revisionId", revisionId)
                .addValue("now", databaseTime(now))) == 1;
    }

    @Override
    public boolean failItem(
            UUID runId,
            UUID itemId,
            String workerId,
            ItemStage stage,
            String errorCode,
            Instant now
    ) {
        return jdbc.update("""
                UPDATE extraction_run_item item
                   SET status = 'FAILED', stage = :stage,
                       error_code = :errorCode, finished_at = :now
                 WHERE item.id = :itemId
                   AND item.run_id = :runId
                   AND item.status = 'RUNNING'
                   AND EXISTS (
                       SELECT 1
                         FROM extraction_run run
                        WHERE run.id = item.run_id
                          AND run.tenant_id = item.tenant_id
                          AND run.status = 'RUNNING'
                          AND run.worker_id = :workerId
                   )
                """, ownership(runId, workerId)
                .addValue("itemId", itemId)
                .addValue("stage", Objects.requireNonNull(stage, "stage must not be null").name())
                .addValue("errorCode", requiredCode(errorCode))
                .addValue("now", databaseTime(now))) == 1;
    }

    @Override
    public boolean finishRun(
            UUID runId,
            String workerId,
            RunStatus finalStatus,
            String errorCode,
            ExtractionGateReport gateReport,
            Instant now
    ) {
        Objects.requireNonNull(finalStatus, "finalStatus must not be null");
        if (!finalStatus.terminal()) {
            throw new IllegalArgumentException("finalStatus must be terminal");
        }
        Boolean finished = transaction.execute(ignored -> finishRunTransaction(
                runId,
                workerId,
                finalStatus,
                errorCode,
                gateReport,
                now
        ));
        return Boolean.TRUE.equals(finished);
    }

    @Override
    public Optional<RunSnapshot> requestCancel(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            UUID runId,
            Instant now
    ) {
        transaction.executeWithoutResult(ignored -> {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId.value())
                    .addValue("spaceId", spaceId.value())
                    .addValue("runId", runId)
                    .addValue("now", databaseTime(now));
            List<String> statuses = jdbc.queryForList("""
                    SELECT status
                      FROM extraction_run
                     WHERE tenant_id = :tenantId
                       AND space_id = :spaceId
                       AND id = :runId
                       FOR UPDATE
                    """, parameters, String.class);
            if (statuses.isEmpty()) {
                return;
            }
            Boolean publishing = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM extraction_run_item
                         WHERE tenant_id = :tenantId
                           AND run_id = :runId
                           AND status = 'RUNNING'
                           AND stage = 'PUBLISHING'
                    )
                    """, parameters, Boolean.class);
            if (Boolean.TRUE.equals(publishing)) {
                throw new ExtractionPublicationInProgressException();
            }
            jdbc.update("""
                    UPDATE extraction_run
                       SET status = CASE
                                        WHEN status = 'QUEUED' THEN 'CANCELLED'
                                        WHEN status = 'RUNNING' THEN 'CANCEL_REQUESTED'
                                        ELSE status
                                    END,
                           cancel_requested_at = CASE
                                                     WHEN status IN ('QUEUED', 'RUNNING')
                                                         THEN :now
                                                     ELSE cancel_requested_at
                                                 END,
                           finished_at = CASE WHEN status = 'QUEUED' THEN :now ELSE finished_at END,
                           worker_id = CASE WHEN status = 'QUEUED' THEN NULL ELSE worker_id END,
                           heartbeat_at = CASE WHEN status = 'QUEUED' THEN NULL ELSE heartbeat_at END
                     WHERE tenant_id = :tenantId
                       AND space_id = :spaceId
                       AND id = :runId
                    """, parameters);
            if ("QUEUED".equals(statuses.getFirst())) {
                cancelOpenItems(runId, now);
            }
        });
        return find(tenantId, spaceId, runId);
    }

    private boolean finishRunTransaction(
            UUID runId,
            String workerId,
            RunStatus finalStatus,
            String errorCode,
            ExtractionGateReport gateReport,
            Instant now
    ) {
        String requiredCurrent = finalStatus == RunStatus.CANCELLED
                ? "('RUNNING', 'CANCEL_REQUESTED')"
                : "('RUNNING')";
        String successGuard = finalStatus == RunStatus.SUCCEEDED
                ? "AND NOT EXISTS (SELECT 1 FROM extraction_run_item item "
                    + "WHERE item.run_id = run.id AND item.tenant_id = run.tenant_id "
                    + "AND item.status NOT IN ('SUCCEEDED', 'SKIPPED_DUPLICATE'))"
                : "";
        int updated = jdbc.update("""
                UPDATE extraction_run run
                   SET status = :finalStatus, error_code = :errorCode,
                       gate_status = :gateStatus,
                       gate_report_json = CAST(:gateReport AS jsonb),
                       finished_at = :now, heartbeat_at = :now
                 WHERE run.id = :runId
                   AND run.status IN %s
                   AND run.worker_id = :workerId
                   AND (
                       CAST(:gateDatasetId AS varchar(128)) IS NULL
                       OR (
                           run.dataset_id = :gateDatasetId
                           AND run.config_fingerprint = :gateConfigFingerprint
                       )
                   )
                   %s
                """.formatted(requiredCurrent, successGuard), ownership(runId, workerId)
                .addValue("finalStatus", finalStatus.name())
                .addValue("errorCode", errorCode == null ? null : requiredCode(errorCode))
                .addValue("gateStatus", gateReport != null
                                ? gateReport.status().name()
                                : ExtractionGateStatus.NOT_EVALUATED.name())
                .addValue("gateReport", gateReport != null
                        ? gateReportJson(gateReport) : null)
                .addValue("gateDatasetId", gateReport == null ? null : gateReport.datasetId())
                .addValue("gateConfigFingerprint", gateReport == null
                        ? null : gateReport.configFingerprint())
                .addValue("now", databaseTime(now)));
        if (updated != 1) {
            return false;
        }
        if (finalStatus == RunStatus.CANCELLED) {
            cancelOpenItems(runId, now);
        } else if (finalStatus == RunStatus.FAILED) {
            jdbc.update("""
                    UPDATE extraction_run_item
                       SET status = 'FAILED', error_code = :errorCode,
                           finished_at = :now
                     WHERE run_id = :runId
                       AND status IN ('QUEUED', 'RUNNING')
                    """, new MapSqlParameterSource()
                    .addValue("runId", runId)
                    .addValue("errorCode", requiredCode(errorCode))
                    .addValue("now", databaseTime(now)));
        }
        return true;
    }

    private void cancelOpenItems(UUID runId, Instant now) {
        jdbc.update("""
                UPDATE extraction_run_item
                   SET status = 'CANCELLED', finished_at = :now
                 WHERE run_id = :runId
                   AND status IN ('QUEUED', 'RUNNING')
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("now", databaseTime(now)));
    }

    private Optional<RunSnapshot> snapshotById(UUID runId) {
        return snapshots(
                "WHERE run.id = :runId",
                new MapSqlParameterSource("runId", runId),
                ""
        ).stream().findFirst();
    }

    private List<RunSnapshot> snapshots(
            String predicate,
            MapSqlParameterSource parameters,
            String suffix
    ) {
        List<RunRow> runs = jdbc.query("""
                SELECT run.id, run.tenant_id, run.space_id, run.mode, run.status,
                       run.language, run.config_version, run.config_fingerprint,
                       run.config_snapshot_json::text AS config_snapshot_json,
                       run.dataset_id, run.baseline_run_id, run.gate_status,
                       run.gate_report_json::text AS gate_report_json,
                       run.created_by, run.error_code,
                       run.created_at, run.ready_at, run.started_at, run.finished_at,
                       run.heartbeat_at
                  FROM extraction_run run
                %s
                 ORDER BY run.created_at DESC, run.id
                %s
                """.formatted(predicate, suffix), parameters, (row, number) -> runRow(row));
        if (runs.isEmpty()) {
            return List.of();
        }
        List<UUID> runIds = runs.stream().map(RunRow::id).toList();
        Map<UUID, List<RunItem>> items = new LinkedHashMap<>();
        jdbc.query("""
                SELECT item.run_id, item.id AS item_id, item.status AS item_status,
                       item.external_id, item.title, item.authority,
                       item.stage, item.error_code, item.parser_id,
                       item.document_id, item.revision_id,
                       item.processor_version,
                       item.element_count, item.chunk_count,
                       item.parse_duration_ms, item.clean_duration_ms,
                       item.chunk_duration_ms,
                       item.diagnostics_json::text AS diagnostics_json,
                       item.preview_json::text AS preview_json,
                       item.preview_truncated,
                       item.started_at AS item_started_at,
                       item.finished_at AS item_finished_at,
                       asset.id AS asset_id, asset.tenant_id, asset.space_id,
                       asset.object_id, asset.storage_id, asset.original_file_name,
                       asset.media_type, asset.content_length, asset.checksum_sha256,
                       asset.stored_at
                  FROM extraction_run_item item
                  JOIN source_asset asset
                    ON asset.tenant_id = item.tenant_id
                   AND asset.id = item.source_asset_id
                 WHERE item.run_id IN (:runIds)
                 ORDER BY item.created_at, item.id
                """, new MapSqlParameterSource("runIds", runIds), row -> {
            UUID runId = row.getObject("run_id", UUID.class);
            items.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(runItem(row));
        });
        return runs.stream()
                .map(run -> run.snapshot(items.getOrDefault(run.id(), List.of())))
                .toList();
    }

    private RunRow runRow(ResultSet row) throws SQLException {
        ExtractionConfigSnapshot snapshot = configSnapshot(
                row.getString("config_snapshot_json")
        );
        if (!snapshot.fingerprint().equals(row.getString("config_fingerprint"))) {
            throw new IllegalStateException("stored config fingerprint differs from snapshot");
        }
        return new RunRow(
                row.getObject("id", UUID.class),
                new TenantId(row.getString("tenant_id")),
                new KnowledgeSpaceId(row.getString("space_id")),
                ExtractionMode.valueOf(row.getString("mode")),
                RunStatus.valueOf(row.getString("status")),
                row.getString("language"),
                row.getLong("config_version"),
                snapshot,
                row.getString("dataset_id"),
                row.getObject("baseline_run_id", UUID.class),
                ExtractionGateStatus.valueOf(row.getString("gate_status")),
                gateReport(row.getString("gate_report_json")),
                new PrincipalId(row.getString("created_by")),
                row.getString("error_code"),
                instant(row, "created_at"),
                nullableInstant(row, "ready_at"),
                nullableInstant(row, "started_at"),
                nullableInstant(row, "finished_at"),
                nullableInstant(row, "heartbeat_at")
        );
    }

    private RunItem runItem(ResultSet row) throws SQLException {
        ItemDiagnostics diagnostics = row.getString("parser_id") == null
                ? null
                : itemDiagnostics(row);
        var asset = new SourceAsset(
                row.getObject("asset_id", UUID.class),
                new TenantId(row.getString("tenant_id")),
                new KnowledgeSpaceId(row.getString("space_id")),
                row.getString("object_id"),
                row.getString("storage_id"),
                row.getString("original_file_name"),
                row.getString("media_type"),
                row.getLong("content_length"),
                row.getString("checksum_sha256"),
                instant(row, "stored_at")
        );
        ExtractionPreview preview = preview(row.getString("preview_json"));
        if ((preview != null && preview.truncated()) != row.getBoolean("preview_truncated")) {
            throw new IllegalStateException("stored preview truncation flag is inconsistent");
        }
        return new RunItem(
                row.getObject("item_id", UUID.class),
                asset,
                new PublicationAttributes(
                        row.getString("external_id"),
                        row.getString("title"),
                        row.getInt("authority")
                ),
                ItemStatus.valueOf(row.getString("item_status")),
                ItemStage.valueOf(row.getString("stage")),
                row.getString("error_code"),
                diagnostics,
                preview,
                row.getObject("document_id", UUID.class) == null
                        ? null
                        : new DocumentId(row.getObject("document_id", UUID.class)),
                row.getObject("revision_id", UUID.class),
                nullableInstant(row, "item_started_at"),
                nullableInstant(row, "item_finished_at")
        );
    }

    /** 基础列和强类型 JSON 明细共同组成完整的逐文件诊断。 */
    private ItemDiagnostics itemDiagnostics(ResultSet row) throws SQLException {
        DiagnosticDetails details = diagnosticDetails(row.getString("diagnostics_json"));
        return new ItemDiagnostics(
                row.getString("parser_id"),
                row.getString("processor_version"),
                row.getInt("element_count"),
                row.getInt("chunk_count"),
                row.getLong("parse_duration_ms"),
                row.getLong("clean_duration_ms"),
                row.getLong("chunk_duration_ms"),
                details.cleaning(),
                details.chunking()
        );
    }

    /** 只序列化固定的 Clean/Chunk 诊断，不把来源内容带入 JSON。 */
    String diagnosticsJson(ItemDiagnostics diagnostics) {
        try {
            return jsonMapper.writeValueAsString(new DiagnosticDetails(
                    diagnostics.cleaning(),
                    diagnostics.chunking()
            ));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "extraction diagnostics cannot be serialized",
                    failure
            );
        }
    }

    /** 从数据库恢复固定结构，拒绝缺失或任意形状的历史 JSON。 */
    DiagnosticDetails diagnosticDetails(String json) {
        if (json == null) {
            throw new IllegalStateException("stored extraction diagnostics are incomplete");
        }
        try {
            return jsonMapper.readValue(json, DiagnosticDetails.class);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "stored extraction diagnostics are invalid",
                    failure
            );
        }
    }

    /** 配置快照以固定强类型 JSON 保存，不接受任意 Map。 */
    String configSnapshotJson(ExtractionConfigSnapshot snapshot) {
        return writeJson(snapshot, "extraction config snapshot");
    }

    /** 从数据库恢复完整有效配置，供 Worker 直接执行创建时语义。 */
    ExtractionConfigSnapshot configSnapshot(String json) {
        return readJson(json, ExtractionConfigSnapshot.class, "extraction config snapshot");
    }

    /** 预览正文只在该受控字段中序列化；调用方禁止记录返回 JSON。 */
    String previewJson(ExtractionPreview preview) {
        return writeJson(preview, "extraction preview");
    }

    /** 恢复有界 Element/Chunk 结构和来源范围。 */
    ExtractionPreview preview(String json) {
        return json == null ? null : readJson(
                json,
                ExtractionPreview.class,
                "extraction preview"
        );
    }

    /** Gate 的时间使用 ISO-8601 字符串，避免 Adapter 依赖 Jackson 时间模块。 */
    String gateReportJson(ExtractionGateReport report) {
        return writeJson(new StoredGateReport(
                report.status(),
                report.datasetId(),
                report.datasetVersion(),
                report.configFingerprint(),
                report.evaluatedAt().toString(),
                report.cases(),
                report.errorCode()
        ), "extraction gate report");
    }

    /** 恢复真实 Runner 报告；空 JSON 明确表示尚未评测。 */
    ExtractionGateReport gateReport(String json) {
        if (json == null) {
            return null;
        }
        StoredGateReport stored = readJson(
                json,
                StoredGateReport.class,
                "extraction gate report"
        );
        return new ExtractionGateReport(
                stored.status(),
                stored.datasetId(),
                stored.datasetVersion(),
                stored.configFingerprint(),
                Instant.parse(stored.evaluatedAt()),
                stored.cases(),
                stored.errorCode()
        );
    }

    private String writeJson(Object value, String name) {
        try {
            return jsonMapper.writeValueAsString(Objects.requireNonNull(value));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(name + " cannot be serialized", failure);
        }
    }

    private <T> T readJson(String json, Class<T> type, String name) {
        if (json == null) {
            throw new IllegalStateException("stored " + name + " is incomplete");
        }
        try {
            return jsonMapper.readValue(json, type);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("stored " + name + " is invalid", failure);
        }
    }

    private static MapSqlParameterSource sourceParameters(
            UUID runId,
            SourceAsset source,
            Instant now
    ) {
        return new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("tenantId", source.tenantId().value())
                .addValue("spaceId", source.spaceId().value())
                .addValue("assetId", source.id())
                .addValue("objectId", source.objectId())
                .addValue("storageId", source.storageId())
                .addValue("fileName", source.fileName())
                .addValue("mediaType", source.mediaType())
                .addValue("contentLength", source.contentLength())
                .addValue("checksum", source.checksumSha256())
                .addValue("storedAt", databaseTime(source.storedAt()))
                .addValue("createdAt", databaseTime(now));
    }

    /** 统一成功写入的诊断参数，避免 TEST_ONLY 与 INGEST 漂移成两套 JSON 形状。 */
    private MapSqlParameterSource successParameters(
            UUID runId,
            UUID itemId,
            String workerId,
            ItemDiagnostics diagnostics,
            ExtractionPreview preview,
            Instant now
    ) {
        return ownership(runId, workerId)
                .addValue("itemId", itemId)
                .addValue("parserId", diagnostics.parserId())
                .addValue("processorVersion", diagnostics.processorVersion())
                .addValue("elementCount", diagnostics.elementCount())
                .addValue("chunkCount", diagnostics.chunkCount())
                .addValue("parseDuration", diagnostics.parseDurationMillis())
                .addValue("cleanDuration", diagnostics.cleanDurationMillis())
                .addValue("chunkDuration", diagnostics.chunkDurationMillis())
                .addValue("diagnostics", diagnosticsJson(diagnostics))
                .addValue("preview", preview == null ? null : previewJson(preview))
                .addValue("previewTruncated", preview != null && preview.truncated())
                .addValue("now", databaseTime(now));
    }

    private static MapSqlParameterSource ownership(UUID runId, String workerId) {
        return new MapSqlParameterSource()
                .addValue("runId", Objects.requireNonNull(runId, "runId must not be null"))
                .addValue("workerId", requiredWorkerId(workerId));
    }

    private static String requiredWorkerId(String value) {
        Objects.requireNonNull(value, "workerId must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("workerId is blank or too long");
        }
        return normalized;
    }

    private static String requiredCode(String value) {
        Objects.requireNonNull(value, "errorCode must not be null");
        String normalized = value.strip();
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        return normalized;
    }

    /** JSONB 中唯一允许的固定诊断形状。 */
    record DiagnosticDetails(
            CleaningDiagnostics cleaning,
            ChunkDiagnostics chunking
    ) {

        DiagnosticDetails {
            Objects.requireNonNull(cleaning, "cleaning must not be null");
            Objects.requireNonNull(chunking, "chunking must not be null");
        }
    }

    /** PostgreSQL JSON 中 Gate 的稳定传输形状。 */
    record StoredGateReport(
            ExtractionGateStatus status,
            String datasetId,
            String datasetVersion,
            String configFingerprint,
            String evaluatedAt,
            List<ExtractionGateReport.CaseResult> cases,
            String errorCode
    ) {
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(
                Objects.requireNonNull(value, "time must not be null"),
                ZoneOffset.UTC
        );
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record RunRow(
            UUID id,
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            ExtractionMode mode,
            RunStatus status,
            String language,
            long configVersion,
            ExtractionConfigSnapshot configSnapshot,
            String datasetId,
            UUID baselineRunId,
            ExtractionGateStatus gateStatus,
            ExtractionGateReport gateReport,
            PrincipalId createdBy,
            String errorCode,
            Instant createdAt,
            Instant readyAt,
            Instant startedAt,
            Instant finishedAt,
            Instant heartbeatAt
    ) {

        private RunSnapshot snapshot(List<RunItem> items) {
            return new RunSnapshot(
                    id,
                    tenantId,
                    spaceId,
                    mode,
                    status,
                    language,
                    configVersion,
                    configSnapshot,
                    datasetId,
                    baselineRunId,
                    gateStatus,
                    gateReport,
                    createdBy,
                    errorCode,
                    createdAt,
                    readyAt,
                    startedAt,
                    finishedAt,
                    heartbeatAt,
                    items
            );
        }
    }
}
