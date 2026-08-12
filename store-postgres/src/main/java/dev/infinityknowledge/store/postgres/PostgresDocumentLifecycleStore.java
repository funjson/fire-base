package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.management.DocumentLifecycleConflictException;
import dev.infinityknowledge.spi.management.DocumentLifecycleStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL adapter for soft, reversible document lifecycle transitions. */
public final class PostgresDocumentLifecycleStore implements DocumentLifecycleStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PostgresDocumentLifecycleStore(
            JdbcTemplate jdbc,
            TransactionTemplate transaction
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(
                transaction,
                "transaction must not be null"
        );
    }

    @Override
    public Optional<DocumentState> transition(
            TenantId tenantId,
            UUID documentId,
            long expectedVersion,
            DocumentStatus targetStatus,
            Instant occurredAt
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (targetStatus != DocumentStatus.ACTIVE
                && targetStatus != DocumentStatus.ARCHIVED
                && targetStatus != DocumentStatus.DELETED) {
            throw new IllegalArgumentException(
                    "targetStatus must be ACTIVE, ARCHIVED or DELETED"
            );
        }
        return Objects.requireNonNull(transaction.execute(status -> transitionLocked(
                tenantId, documentId, expectedVersion, targetStatus, occurredAt
        )), "lifecycle transaction must return an Optional");
    }

    private Optional<DocumentState> transitionLocked(
            TenantId tenantId,
            UUID documentId,
            long expectedVersion,
            DocumentStatus targetStatus,
            Instant occurredAt
    ) {
        LockedDocument current = jdbc.query("""
                SELECT space_id, status, version, active_revision_id, updated_at
                  FROM knowledge_document
                 WHERE tenant_id = ? AND id = ?
                 FOR UPDATE
                """, result -> result.next() ? lockedDocument(result) : null,
                tenantId.value(), documentId);
        if (current == null) {
            return Optional.empty();
        }
        if (current.version() != expectedVersion) {
            throw new DocumentLifecycleConflictException(
                    "document version conflict"
            );
        }
        if (targetStatus == DocumentStatus.ACTIVE && current.activeRevisionId() == null) {
            throw new DocumentLifecycleConflictException(
                    "a document without an active revision cannot be restored"
            );
        }
        if (current.status() == targetStatus) {
            return Optional.of(new DocumentState(
                    documentId,
                    current.spaceId(),
                    current.status(),
                    current.version(),
                    current.updatedAt(),
                    false
            ));
        }
        if (current.status() == DocumentStatus.DELETED
                && targetStatus == DocumentStatus.ARCHIVED) {
            throw new DocumentLifecycleConflictException(
                    "a deleted document can only be restored to ACTIVE"
            );
        }

        long nextVersion = current.version() + 1;
        jdbc.update("""
                UPDATE knowledge_document
                   SET status = ?, version = ?, updated_at = greatest(updated_at, ?)
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, targetStatus.name(), nextVersion, databaseTime(occurredAt),
                tenantId.value(), documentId, current.version());
        if (targetStatus == DocumentStatus.ACTIVE) {
            requeueExistingProjections(tenantId, current.activeRevisionId(), occurredAt);
        }
        return Optional.of(new DocumentState(
                documentId,
                current.spaceId(),
                targetStatus,
                nextVersion,
                current.updatedAt().isAfter(occurredAt) ? current.updatedAt() : occurredAt,
                true
        ));
    }

    private void requeueExistingProjections(
            TenantId tenantId,
            UUID revisionId,
            Instant occurredAt
    ) {
        jdbc.update("""
                UPDATE projection_job
                   SET status = CASE WHEN status = 'RUNNING' THEN status ELSE 'PENDING' END,
                       requeue_requested = CASE
                           WHEN status = 'RUNNING' THEN true ELSE false END,
                       attempt_count = CASE
                           WHEN status = 'RUNNING' THEN attempt_count ELSE 0 END,
                       available_at = CASE
                           WHEN status = 'RUNNING' THEN available_at ELSE ? END,
                       last_error_code = CASE
                           WHEN status = 'RUNNING' THEN last_error_code ELSE NULL END,
                       completed_at = CASE
                           WHEN status = 'RUNNING' THEN completed_at ELSE NULL END,
                       updated_at = greatest(updated_at, ?)
                 WHERE tenant_id = ? AND revision_id = ?
                """, databaseTime(occurredAt), databaseTime(occurredAt),
                tenantId.value(), revisionId);
    }

    private static LockedDocument lockedDocument(ResultSet result) throws SQLException {
        return new LockedDocument(
                result.getString("space_id"),
                DocumentStatus.valueOf(result.getString("status")),
                result.getLong("version"),
                result.getObject("active_revision_id", UUID.class),
                result.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record LockedDocument(
            String spaceId,
            DocumentStatus status,
            long version,
            UUID activeRevisionId,
            Instant updatedAt
    ) {
    }
}
