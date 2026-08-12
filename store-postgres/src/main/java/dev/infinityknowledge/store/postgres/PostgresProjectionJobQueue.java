package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.indexing.ProjectionJob;
import dev.infinityknowledge.spi.indexing.ProjectionJobQueue;
import dev.infinityknowledge.spi.indexing.ProjectionJobState;
import dev.infinityknowledge.spi.indexing.ProjectionJobStatusStore;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PostgreSQL lease queue using {@code FOR UPDATE SKIP LOCKED}.
 */
public final class PostgresProjectionJobQueue
        implements ProjectionJobQueue, ProjectionJobStatusStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * Creates the queue.
     */
    public PostgresProjectionJobQueue(
            JdbcTemplate jdbc,
            TransactionTemplate transaction
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    @Override
    public List<ProjectionJob> claim(
            String workerId,
            Set<ProjectionType> supportedTypes,
            int limit,
            Duration leaseDuration,
            Instant now
    ) {
        workerId = requiredWorker(workerId);
        supportedTypes = Set.copyOf(
                Objects.requireNonNull(supportedTypes, "supportedTypes must not be null")
        );
        if (supportedTypes.isEmpty()) {
            return List.of();
        }
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        String claimedBy = workerId;
        String typeLiterals = supportedTypes.stream()
                .map(type -> "'" + type.name() + "'")
                .sorted()
                .collect(Collectors.joining(","));
        List<ProjectionJob> result = transaction.execute(status -> jdbc.query("""
                WITH candidates AS (
                    SELECT id
                    FROM projection_job
                    WHERE available_at <= ?
                      AND projection_type IN (%s)
                      AND (
                          status IN ('PENDING', 'RETRY')
                          OR (status = 'RUNNING' AND lease_until < ?)
                      )
                    ORDER BY available_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE projection_job AS job
                SET status = 'RUNNING',
                    attempt_count = attempt_count + 1,
                    lease_token = lease_token + 1,
                    requeue_requested = FALSE,
                    lease_owner = ?,
                    lease_until = ?,
                    completed_at = NULL,
                    updated_at = ?
                FROM candidates
                WHERE job.id = candidates.id
                RETURNING job.id, job.tenant_id, job.space_id, job.document_id,
                          job.revision_id, job.projection_type, job.attempt_count,
                          job.lease_token
                """.formatted(typeLiterals),
                (resultSet, rowNumber) -> new ProjectionJob(
                        resultSet.getObject("id", UUID.class),
                        new TenantId(resultSet.getString("tenant_id")),
                        new KnowledgeSpaceId(resultSet.getString("space_id")),
                        new DocumentId(resultSet.getObject("document_id", UUID.class)),
                        resultSet.getObject("revision_id", UUID.class),
                        ProjectionType.valueOf(resultSet.getString("projection_type")),
                        resultSet.getInt("attempt_count"),
                        resultSet.getLong("lease_token")
                ),
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                limit,
                claimedBy,
                now.plus(leaseDuration).atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC)
        ));
        return result == null ? List.of() : List.copyOf(result);
    }

    @Override
    public boolean complete(
            UUID jobId,
            String workerId,
            long leaseToken,
            Instant now
    ) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        workerId = requiredWorker(workerId);
        requireLeaseToken(leaseToken);
        Objects.requireNonNull(now, "now must not be null");
        return jdbc.update("""
                UPDATE projection_job
                SET status = CASE
                        WHEN requeue_requested THEN 'PENDING'
                        ELSE 'SUCCEEDED'
                    END,
                    attempt_count = CASE
                        WHEN requeue_requested THEN 0
                        ELSE attempt_count
                    END,
                    available_at = CASE
                        WHEN requeue_requested THEN ?
                        ELSE available_at
                    END,
                    lease_owner = NULL,
                    lease_until = NULL,
                    completed_at = CASE
                        WHEN requeue_requested THEN NULL
                        ELSE ?
                    END,
                    last_error_code = NULL,
                    requeue_requested = FALSE,
                    updated_at = ?
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND lease_until >= ?
                """,
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                jobId,
                workerId,
                leaseToken,
                now.atOffset(ZoneOffset.UTC)
        ) == 1;
    }

    @Override
    public boolean heartbeat(
            UUID jobId,
            String workerId,
            long leaseToken,
            Instant leaseUntil,
            Instant now
    ) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        workerId = requiredWorker(workerId);
        requireLeaseToken(leaseToken);
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        return jdbc.update("""
                UPDATE projection_job
                   SET lease_until = ?, updated_at = ?
                 WHERE id = ?
                   AND status = 'RUNNING'
                   AND lease_owner = ?
                   AND lease_token = ?
                   AND lease_until >= ?
                """,
                leaseUntil.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                jobId,
                workerId,
                leaseToken,
                now.atOffset(ZoneOffset.UTC)
        ) == 1;
    }

    @Override
    public boolean fail(
            UUID jobId,
            String workerId,
            long leaseToken,
            String errorCode,
            Instant availableAt,
            boolean dead,
            Instant now
    ) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        workerId = requiredWorker(workerId);
        requireLeaseToken(leaseToken);
        errorCode = requiredErrorCode(errorCode);
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return jdbc.update("""
                UPDATE projection_job
                SET status = CASE
                        WHEN requeue_requested THEN 'PENDING'
                        ELSE ?
                    END,
                    attempt_count = CASE
                        WHEN requeue_requested THEN 0
                        ELSE attempt_count
                    END,
                    lease_owner = NULL,
                    lease_until = NULL,
                    available_at = CASE
                        WHEN requeue_requested THEN ?
                        ELSE ?
                    END,
                    last_error_code = CASE
                        WHEN requeue_requested THEN NULL
                        ELSE ?
                    END,
                    completed_at = NULL,
                    requeue_requested = FALSE,
                    updated_at = ?
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_token = ?
                  AND lease_until >= ?
                """,
                dead ? "DEAD" : "RETRY",
                now.atOffset(ZoneOffset.UTC),
                availableAt.atOffset(ZoneOffset.UTC),
                errorCode,
                now.atOffset(ZoneOffset.UTC),
                jobId,
                workerId,
                leaseToken,
                now.atOffset(ZoneOffset.UTC)
        ) == 1;
    }

    @Override
    public List<ProjectionJobState> findByDocument(
            TenantId tenantId,
            DocumentId documentId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        return List.copyOf(jdbc.query("""
                SELECT j.id, j.projection_type, j.status, j.attempt_count,
                       j.last_error_code, j.available_at, j.updated_at
                  FROM projection_job j
                  JOIN knowledge_document d
                    ON d.tenant_id = j.tenant_id
                   AND d.id = j.document_id
                   AND d.active_revision_id = j.revision_id
                   AND d.status = 'ACTIVE'
                 WHERE j.tenant_id = ? AND j.document_id = ?
                 ORDER BY j.created_at, j.projection_type
                """,
                (resultSet, rowNumber) -> new ProjectionJobState(
                        resultSet.getObject("id", UUID.class),
                        ProjectionType.valueOf(resultSet.getString("projection_type")),
                        resultSet.getString("status"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getString("last_error_code"),
                        resultSet.getObject(
                                "available_at",
                                java.time.OffsetDateTime.class
                        ).toInstant(),
                        resultSet.getObject(
                                "updated_at",
                                java.time.OffsetDateTime.class
                        ).toInstant()
                ),
                tenantId.value(),
                documentId.value()
        ));
    }

    @Override
    public boolean requeueDead(
            TenantId tenantId,
            DocumentId documentId,
            ProjectionType projectionType,
            Instant now
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(projectionType, "projectionType must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return jdbc.update("""
                UPDATE projection_job AS job
                SET status = 'RETRY',
                    attempt_count = 0,
                    available_at = ?,
                    requeue_requested = FALSE,
                    last_error_code = NULL,
                    completed_at = NULL,
                    updated_at = ?
                FROM knowledge_document AS document
                WHERE document.tenant_id = job.tenant_id
                  AND document.id = job.document_id
                  AND document.active_revision_id = job.revision_id
                  AND document.status = 'ACTIVE'
                  AND job.tenant_id = ?
                  AND job.document_id = ?
                  AND job.projection_type = ?
                  AND job.status = 'DEAD'
                """,
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                tenantId.value(),
                documentId.value(),
                projectionType.name()
        ) == 1;
    }

    @Override
    public int rebuildSpace(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            Set<ProjectionType> projectionTypes,
            Instant now
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        projectionTypes = Set.copyOf(
                Objects.requireNonNull(
                        projectionTypes,
                        "projectionTypes must not be null"
                )
        );
        Objects.requireNonNull(now, "now must not be null");
        if (projectionTypes.isEmpty()) {
            return 0;
        }
        Set<ProjectionType> types = projectionTypes;
        Integer rebuilt = transaction.execute(status -> {
            List<ActiveRevision> revisions = jdbc.query("""
                    SELECT d.id, d.active_revision_id
                      FROM knowledge_document d
                     WHERE d.tenant_id = ?
                       AND d.space_id = ?
                       AND d.status = 'ACTIVE'
                       AND d.active_revision_id IS NOT NULL
                       AND EXISTS (
                            SELECT 1
                              FROM knowledge_chunk c
                             WHERE c.tenant_id = d.tenant_id
                               AND c.document_id = d.id
                               AND c.revision_id = d.active_revision_id
                       )
                     ORDER BY d.id
                    """, (row, number) -> new ActiveRevision(
                    row.getObject("id", UUID.class),
                    row.getObject("active_revision_id", UUID.class)
            ), tenantId.value(), spaceId.value());
            int affected = 0;
            for (ActiveRevision revision : revisions) {
                for (ProjectionType type : types) {
                    affected += jdbc.update("""
                            INSERT INTO projection_job
                                (id, tenant_id, space_id, document_id, revision_id,
                                 projection_type, status, attempt_count, available_at,
                                 created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                            ON CONFLICT (tenant_id, revision_id, projection_type)
                            DO UPDATE SET
                                status = CASE
                                    WHEN projection_job.status = 'RUNNING'
                                        THEN projection_job.status
                                    ELSE 'PENDING'
                                END,
                                attempt_count = CASE
                                    WHEN projection_job.status = 'RUNNING'
                                        THEN projection_job.attempt_count
                                    ELSE 0
                                END,
                                available_at = CASE
                                    WHEN projection_job.status = 'RUNNING'
                                        THEN projection_job.available_at
                                    ELSE EXCLUDED.available_at
                                END,
                                lease_owner = CASE
                                    WHEN projection_job.status = 'RUNNING'
                                        THEN projection_job.lease_owner
                                    ELSE NULL
                                END,
                                lease_until = CASE
                                    WHEN projection_job.status = 'RUNNING'
                                        THEN projection_job.lease_until
                                    ELSE NULL
                                END,
                                requeue_requested = projection_job.status = 'RUNNING',
                                last_error_code = CASE
                                    WHEN projection_job.status = 'RUNNING'
                                        THEN projection_job.last_error_code
                                    ELSE NULL
                                END,
                                completed_at = CASE
                                    WHEN projection_job.status = 'RUNNING'
                                        THEN projection_job.completed_at
                                    ELSE NULL
                                END,
                                updated_at = EXCLUDED.updated_at
                            WHERE projection_job.status IN ('RUNNING', 'SUCCEEDED', 'DEAD')
                            """,
                            UUID.randomUUID(),
                            tenantId.value(),
                            spaceId.value(),
                            revision.documentId(),
                            revision.revisionId(),
                            type.name(),
                            now.atOffset(ZoneOffset.UTC),
                            now.atOffset(ZoneOffset.UTC),
                            now.atOffset(ZoneOffset.UTC)
                    );
                }
            }
            return affected;
        });
        return rebuilt == null ? 0 : rebuilt;
    }

    private static String requiredWorker(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        String value = workerId.strip();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("workerId must contain 1..128 characters");
        }
        return value;
    }

    private static String requiredErrorCode(String errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        String value = errorCode.strip();
        if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("errorCode must be a stable uppercase code");
        }
        return value;
    }

    private static void requireLeaseToken(long leaseToken) {
        if (leaseToken < 1) {
            throw new IllegalArgumentException("leaseToken must be positive");
        }
    }

    private record ActiveRevision(UUID documentId, UUID revisionId) {
    }
}
