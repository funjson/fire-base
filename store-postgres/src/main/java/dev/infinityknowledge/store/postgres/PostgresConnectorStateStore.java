package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.connector.ConnectorCursor;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL connector state adapter.
 */
public final class PostgresConnectorStateStore implements ConnectorStateStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public PostgresConnectorStateStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @Override
    public boolean configure(ConnectorRegistration registration) {
        Objects.requireNonNull(registration, "registration must not be null");
        Map<String, Object> persistedConfiguration =
                new LinkedHashMap<>(registration.configuration());
        persistedConfiguration.put("authority", registration.authority());
        OffsetDateTime occurredAt = databaseTime(registration.occurredAt());
        // Connector ID 是稳定来源身份；配置更新不得把既有来源迁移到其他 Space 或改成其他类型。
        int affected = jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, created_at, updated_at)
                SELECT ?, ?, s.id, ?, ?, ?::jsonb, 'ACTIVE', ?, ?
                  FROM knowledge_space s
                 WHERE s.tenant_id = ? AND s.id = ? AND s.status = 'ACTIVE'
                ON CONFLICT (tenant_id, id) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    config_json = EXCLUDED.config_json,
                    status = 'ACTIVE',
                    version = connector_instance.version + 1,
                    updated_at = EXCLUDED.updated_at
                WHERE connector_instance.space_id = EXCLUDED.space_id
                  AND connector_instance.connector_type = EXCLUDED.connector_type
                """,
                registration.tenantId().value(),
                registration.connectorId(),
                registration.type(),
                registration.displayName(),
                json(persistedConfiguration),
                occurredAt,
                occurredAt,
                registration.tenantId().value(),
                registration.spaceId().value()
        );
        return affected > 0;
    }

    @Override
    public Optional<SourceConnectorDefinition> findActive(
            TenantId tenantId,
            String connectorId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String normalizedConnectorId = requireText(connectorId, "connectorId");
        SourceConnectorDefinition definition = jdbc.query("""
                SELECT id, space_id, connector_type, display_name, config_json::text
                  FROM connector_instance
                 WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                """, result -> {
            if (!result.next()) {
                return null;
            }
            Map<String, Object> persisted = objectMap(result.getString("config_json"));
            int authority = integer(persisted.remove("authority"), 70);
            Map<String, String> configuration = new LinkedHashMap<>();
            persisted.forEach((key, value) -> configuration.put(key, String.valueOf(value)));
            return new SourceConnectorDefinition(
                    result.getString("id"),
                    new KnowledgeSpaceId(result.getString("space_id")),
                    result.getString("connector_type"),
                    result.getString("display_name"),
                    authority,
                    configuration
            );
        }, tenantId.value(), normalizedConnectorId);
        return Optional.ofNullable(definition);
    }

    @Override
    public boolean tryStart(SynchronizationRun run) {
        Objects.requireNonNull(run, "run must not be null");
        try {
            return jdbc.update("""
                    INSERT INTO connector_sync_run
                        (id, tenant_id, connector_id, snapshot_id, status,
                         principal_json, started_at)
                    VALUES (?, ?, ?, ?, 'PENDING', ?::jsonb, ?)
                    """,
                    run.runId(),
                    run.tenantId().value(),
                    run.connectorId(),
                    run.snapshotId(),
                    json(principal(run.principal())),
                    databaseTime(run.startedAt())
            ) > 0;
        } catch (DuplicateKeyException running) {
            return false;
        }
    }

    @Override
    public Optional<SynchronizationLease> claim(
            TenantId tenantId,
            UUID runId,
            String leaseOwner,
            Instant leaseUntil,
            Instant now
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        String owner = requireText(leaseOwner, "leaseOwner");
        requireLeaseWindow(leaseUntil, now);
        SynchronizationLease lease = jdbc.query("""
                WITH candidate AS (
                    SELECT id
                      FROM connector_sync_run
                     WHERE tenant_id = ? AND id = ?
                       AND (status = 'PENDING'
                            OR (status = 'RUNNING' AND lease_until < ?))
                     FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE connector_sync_run run
                       SET status = 'RUNNING',
                           lease_owner = ?,
                           lease_token = run.lease_token + 1,
                           lease_until = ?,
                           error_code = NULL,
                           completed_at = NULL
                      FROM candidate
                     WHERE run.id = candidate.id
                    RETURNING run.*
                )
                SELECT claimed.id, claimed.tenant_id, claimed.connector_id,
                       claimed.snapshot_id, claimed.principal_json::text,
                       claimed.records_seen, claimed.records_changed,
                       claimed.lease_owner, claimed.lease_token, claimed.lease_until,
                       coalesce(checkpoint.cursor_json, '{}'::jsonb)::text AS cursor_json
                  FROM claimed
                  LEFT JOIN connector_checkpoint checkpoint
                    ON checkpoint.tenant_id = claimed.tenant_id
                   AND checkpoint.connector_id = claimed.connector_id
                   AND checkpoint.snapshot_id = claimed.snapshot_id
                """, result -> result.next() ? lease(result) : null,
                tenantId.value(), runId, databaseTime(now), owner, databaseTime(leaseUntil));
        return Optional.ofNullable(lease);
    }

    @Override
    public List<SynchronizationLease> claimAvailable(
            String leaseOwner,
            int limit,
            Instant leaseUntil,
            Instant now
    ) {
        String owner = requireText(leaseOwner, "leaseOwner");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        requireLeaseWindow(leaseUntil, now);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                      FROM connector_sync_run
                     WHERE status = 'PENDING'
                        OR (status = 'RUNNING' AND lease_until < ?)
                     ORDER BY started_at, id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE connector_sync_run run
                       SET status = 'RUNNING',
                           lease_owner = ?,
                           lease_token = run.lease_token + 1,
                           lease_until = ?,
                           error_code = NULL,
                           completed_at = NULL
                      FROM candidate
                     WHERE run.id = candidate.id
                    RETURNING run.*
                )
                SELECT claimed.id, claimed.tenant_id, claimed.connector_id,
                       claimed.snapshot_id, claimed.principal_json::text,
                       claimed.records_seen, claimed.records_changed,
                       claimed.lease_owner, claimed.lease_token, claimed.lease_until,
                       coalesce(checkpoint.cursor_json, '{}'::jsonb)::text AS cursor_json
                  FROM claimed
                  LEFT JOIN connector_checkpoint checkpoint
                    ON checkpoint.tenant_id = claimed.tenant_id
                   AND checkpoint.connector_id = claimed.connector_id
                   AND checkpoint.snapshot_id = claimed.snapshot_id
                 ORDER BY claimed.started_at, claimed.id
                """, (result, row) -> lease(result),
                databaseTime(now), limit, owner, databaseTime(leaseUntil));
    }

    @Override
    public Optional<SynchronizationStatus> findRun(TenantId tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        SynchronizationStatus status = jdbc.query("""
                SELECT id, connector_id, status, records_seen, records_changed,
                       records_deleted,
                       error_code, started_at, completed_at
                  FROM connector_sync_run
                 WHERE tenant_id = ? AND id = ?
                """, result -> {
            if (!result.next()) {
                return null;
            }
            OffsetDateTime completedAt = result.getObject(
                    "completed_at",
                    OffsetDateTime.class
            );
            return new SynchronizationStatus(
                    result.getObject("id", UUID.class),
                    result.getString("connector_id"),
                    result.getString("status"),
                    result.getLong("records_seen"),
                    result.getLong("records_changed"),
                    result.getLong("records_deleted"),
                    result.getString("error_code"),
                    result.getObject("started_at", OffsetDateTime.class).toInstant(),
                    completedAt == null ? null : completedAt.toInstant()
            );
        }, tenantId.value(), runId);
        return Optional.ofNullable(status);
    }

    @Override
    public boolean saveCheckpoint(
            SynchronizationLease lease,
            ConnectorCheckpoint checkpoint,
            long recordsSeen,
            long recordsChanged,
            Instant now
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        requireCounts(recordsSeen, recordsChanged);
        Objects.requireNonNull(now, "now must not be null");
        if (!lease.tenantId().equals(checkpoint.tenantId())
                || !lease.connectorId().equals(checkpoint.connectorId())
                || !lease.snapshotId().equals(checkpoint.snapshotId())) {
            throw new IllegalArgumentException("checkpoint must belong to the claimed run");
        }
        return jdbc.update("""
                WITH owned AS (
                    UPDATE connector_sync_run
                       SET records_seen = ?, records_changed = ?
                     WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                       AND lease_owner = ? AND lease_token = ? AND lease_until >= ?
                    RETURNING tenant_id, connector_id
                )
                INSERT INTO connector_checkpoint
                    (tenant_id, connector_id, cursor_json, snapshot_id, version, updated_at)
                SELECT owned.tenant_id, owned.connector_id, ?::jsonb, ?, 0, ?
                  FROM owned
                ON CONFLICT (tenant_id, connector_id) DO UPDATE
                SET cursor_json = EXCLUDED.cursor_json,
                    snapshot_id = EXCLUDED.snapshot_id,
                    version = connector_checkpoint.version + 1,
                    updated_at = EXCLUDED.updated_at
                """,
                recordsSeen,
                recordsChanged,
                lease.tenantId().value(),
                lease.runId(),
                lease.leaseOwner(),
                lease.leaseToken(),
                databaseTime(now),
                json(checkpoint.cursor().values()),
                checkpoint.snapshotId(),
                databaseTime(checkpoint.updatedAt())
        ) == 1;
    }

    @Override
    public boolean restartFullSnapshot(
            SynchronizationLease lease,
            Instant now
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Boolean restarted = jdbc.queryForObject("""
                WITH owned AS MATERIALIZED (
                    UPDATE connector_sync_run run
                       SET records_seen = 0,
                           records_changed = 0,
                           snapshot_restart_token = run.lease_token
                     WHERE run.tenant_id = ? AND run.id = ? AND run.status = 'RUNNING'
                       AND run.lease_owner = ? AND run.lease_token = ?
                       AND run.lease_until >= ?
                       AND run.snapshot_restart_token < run.lease_token
                    RETURNING run.id, run.tenant_id, run.connector_id, run.snapshot_id
                ), cleared AS (
                    DELETE FROM connector_snapshot_manifest staged
                     USING owned
                     WHERE staged.run_id = owned.id
                       AND staged.tenant_id = owned.tenant_id
                       AND staged.connector_id = owned.connector_id
                       AND staged.snapshot_id = owned.snapshot_id
                    RETURNING staged.external_id
                ), reset_checkpoint AS (
                    INSERT INTO connector_checkpoint
                        (tenant_id, connector_id, cursor_json, snapshot_id,
                         version, updated_at)
                    SELECT owned.tenant_id, owned.connector_id, '{}'::jsonb,
                           owned.snapshot_id, 0, ?
                      FROM owned
                    ON CONFLICT (tenant_id, connector_id) DO UPDATE
                    SET cursor_json = '{}'::jsonb,
                        snapshot_id = EXCLUDED.snapshot_id,
                        version = connector_checkpoint.version + 1,
                        updated_at = EXCLUDED.updated_at
                    RETURNING connector_id
                )
                SELECT EXISTS (SELECT 1 FROM owned)
                  FROM (SELECT count(*) FROM cleared) cleared_count,
                       (SELECT count(*) FROM reset_checkpoint) checkpoint_count
                """,
                Boolean.class,
                lease.tenantId().value(),
                lease.runId(),
                lease.leaseOwner(),
                lease.leaseToken(),
                databaseTime(now),
                databaseTime(now)
        );
        return Boolean.TRUE.equals(restarted);
    }

    @Override
    public boolean heartbeat(
            SynchronizationLease lease,
            Instant leaseUntil,
            Instant now
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        requireLeaseWindow(leaseUntil, now);
        return jdbc.update("""
                UPDATE connector_sync_run
                   SET lease_until = ?
                 WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                   AND lease_owner = ? AND lease_token = ? AND lease_until >= ?
                """, databaseTime(leaseUntil), lease.tenantId().value(), lease.runId(),
                lease.leaseOwner(), lease.leaseToken(), databaseTime(now)) == 1;
    }

    @Override
    public boolean stageManifest(
            SynchronizationLease lease,
            KnowledgeSpaceId spaceId,
            List<ManifestEntry> entries,
            Instant now
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        Objects.requireNonNull(now, "now must not be null");
        if (entries.isEmpty()) {
            return ownsSnapshotLease(lease, spaceId, now);
        }
        Set<String> identities = new java.util.HashSet<>();
        List<Map<String, String>> serialized = entries.stream().map(entry -> {
            if (!identities.add(entry.externalId())) {
                throw new IllegalArgumentException(
                        "manifest entries must have unique externalId values"
                );
            }
            return Map.of(
                    "external_id", entry.externalId(),
                    "source_uri", entry.sourceUri(),
                    "content_hash", entry.contentHash(),
                    "document_id", entry.documentId().toString()
            );
        }).toList();
        int affected = jdbc.update("""
                WITH owned AS (
                    SELECT run.id, run.tenant_id, run.connector_id, run.snapshot_id
                      FROM connector_sync_run run
                      JOIN connector_instance connector
                        ON connector.tenant_id = run.tenant_id
                       AND connector.id = run.connector_id
                       AND connector.space_id = ?
                     WHERE run.tenant_id = ? AND run.id = ? AND run.status = 'RUNNING'
                       AND run.lease_owner = ? AND run.lease_token = ?
                       AND run.lease_until >= ?
                ), entries AS (
                    SELECT item.external_id, item.source_uri, item.content_hash,
                           item.document_id::uuid AS document_id
                      FROM jsonb_to_recordset(?::jsonb) AS item(
                           external_id text,
                           source_uri text,
                           content_hash text,
                           document_id text
                      )
                )
                INSERT INTO connector_snapshot_manifest
                    (tenant_id, connector_id, space_id, run_id, snapshot_id,
                     external_id, document_id, content_hash, source_uri, observed_at)
                SELECT owned.tenant_id, owned.connector_id, ?, owned.id,
                       owned.snapshot_id, entries.external_id, entries.document_id,
                       entries.content_hash, entries.source_uri, ?
                  FROM owned
                  JOIN entries ON TRUE
                  JOIN knowledge_document document
                    ON document.tenant_id = owned.tenant_id
                   AND document.space_id = ?
                   AND document.connector_id = owned.connector_id
                   AND document.external_id = entries.external_id
                   AND document.id = entries.document_id
                   AND document.status = 'ACTIVE'
                ON CONFLICT (tenant_id, connector_id, snapshot_id, external_id)
                DO UPDATE SET document_id = EXCLUDED.document_id,
                              content_hash = EXCLUDED.content_hash,
                              source_uri = EXCLUDED.source_uri,
                              observed_at = EXCLUDED.observed_at
                """,
                spaceId.value(),
                lease.tenantId().value(),
                lease.runId(),
                lease.leaseOwner(),
                lease.leaseToken(),
                databaseTime(now),
                json(serialized),
                spaceId.value(),
                databaseTime(now),
                spaceId.value()
        );
        return affected == entries.size();
    }

    @Override
    public boolean complete(
            SynchronizationLease lease,
            long recordsSeen,
            long recordsChanged,
            Instant completedAt
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        requireCounts(recordsSeen, recordsChanged);
        return jdbc.update("""
                UPDATE connector_sync_run
                   SET status = 'SUCCEEDED', records_seen = ?,
                       records_changed = ?, completed_at = ?,
                       lease_owner = NULL, lease_until = NULL
                 WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                   AND lease_owner = ? AND lease_token = ? AND lease_until >= ?
                """,
                recordsSeen,
                recordsChanged,
                databaseTime(completedAt),
                lease.tenantId().value(),
                lease.runId(),
                lease.leaseOwner(),
                lease.leaseToken(),
                databaseTime(completedAt)
        ) == 1;
    }

    @Override
    public Optional<SnapshotCompletion> completeFullSnapshot(
            SynchronizationLease lease,
            KnowledgeSpaceId spaceId,
            long recordsSeen,
            long recordsChanged,
            Instant completedAt
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        requireCounts(recordsSeen, recordsChanged);
        Long deleted = jdbc.query("""
                WITH owned AS MATERIALIZED (
                    SELECT run.id, run.tenant_id, run.connector_id, run.snapshot_id
                      FROM connector_sync_run run
                      JOIN connector_instance connector
                        ON connector.tenant_id = run.tenant_id
                       AND connector.id = run.connector_id
                       AND connector.space_id = ?
                      JOIN connector_checkpoint checkpoint
                        ON checkpoint.tenant_id = run.tenant_id
                       AND checkpoint.connector_id = run.connector_id
                       AND checkpoint.snapshot_id = run.snapshot_id
                       AND checkpoint.cursor_json = '{}'::jsonb
                     WHERE run.tenant_id = ? AND run.id = ? AND run.status = 'RUNNING'
                       AND run.lease_owner = ? AND run.lease_token = ?
                       AND run.lease_until >= ?
                     FOR UPDATE OF run
                ), archived AS (
                    UPDATE knowledge_document document
                       SET status = 'ARCHIVED',
                           version = document.version + 1,
                           updated_at = ?
                      FROM connector_manifest manifest, owned
                     WHERE manifest.tenant_id = owned.tenant_id
                       AND manifest.connector_id = owned.connector_id
                       AND manifest.space_id = ?
                       AND document.tenant_id = manifest.tenant_id
                       AND document.id = manifest.document_id
                       AND document.space_id = manifest.space_id
                       AND document.connector_id = manifest.connector_id
                       AND document.status = 'ACTIVE'
                       AND NOT EXISTS (
                           SELECT 1
                             FROM connector_snapshot_manifest seen
                            WHERE seen.tenant_id = owned.tenant_id
                              AND seen.connector_id = owned.connector_id
                              AND seen.snapshot_id = owned.snapshot_id
                              AND seen.external_id = manifest.external_id
                       )
                    RETURNING document.id
                ), promoted AS (
                    INSERT INTO connector_manifest
                        (tenant_id, connector_id, space_id, external_id, document_id,
                         content_hash, source_uri, updated_at)
                    SELECT seen.tenant_id, seen.connector_id, seen.space_id,
                           seen.external_id, seen.document_id, seen.content_hash,
                           seen.source_uri, ?
                      FROM connector_snapshot_manifest seen
                      JOIN owned
                        ON owned.id = seen.run_id
                       AND owned.tenant_id = seen.tenant_id
                       AND owned.connector_id = seen.connector_id
                       AND owned.snapshot_id = seen.snapshot_id
                    ON CONFLICT (tenant_id, connector_id, external_id)
                    DO UPDATE SET space_id = EXCLUDED.space_id,
                                  document_id = EXCLUDED.document_id,
                                  content_hash = EXCLUDED.content_hash,
                                  source_uri = EXCLUDED.source_uri,
                                  updated_at = EXCLUDED.updated_at
                    RETURNING external_id
                ), pruned AS (
                    DELETE FROM connector_manifest manifest
                     USING owned
                     WHERE manifest.tenant_id = owned.tenant_id
                       AND manifest.connector_id = owned.connector_id
                       AND NOT EXISTS (
                           SELECT 1
                             FROM connector_snapshot_manifest seen
                            WHERE seen.tenant_id = owned.tenant_id
                              AND seen.connector_id = owned.connector_id
                              AND seen.snapshot_id = owned.snapshot_id
                              AND seen.external_id = manifest.external_id
                       )
                    RETURNING manifest.external_id
                ), cleared AS (
                    DELETE FROM connector_snapshot_manifest seen
                     USING owned
                     WHERE seen.run_id = owned.id
                    RETURNING seen.external_id
                ), completed AS (
                    UPDATE connector_sync_run run
                       SET status = 'SUCCEEDED',
                           records_seen = ?,
                           records_changed = ?,
                           records_deleted = (SELECT count(*) FROM archived),
                           completed_at = ?,
                           lease_owner = NULL,
                           lease_until = NULL
                      FROM owned
                     WHERE run.id = owned.id
                    RETURNING run.records_deleted
                )
                SELECT records_deleted FROM completed
                """, result -> result.next() ? result.getLong("records_deleted") : null,
                spaceId.value(),
                lease.tenantId().value(),
                lease.runId(),
                lease.leaseOwner(),
                lease.leaseToken(),
                databaseTime(completedAt),
                databaseTime(completedAt),
                spaceId.value(),
                databaseTime(completedAt),
                recordsSeen,
                recordsChanged,
                databaseTime(completedAt)
        );
        return deleted == null
                ? Optional.empty()
                : Optional.of(new SnapshotCompletion(deleted));
    }

    @Override
    public boolean fail(
            SynchronizationLease lease,
            String errorCode,
            Instant completedAt
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Boolean failed = jdbc.queryForObject("""
                WITH failed AS (
                    UPDATE connector_sync_run
                       SET status = 'FAILED', error_code = ?, completed_at = ?,
                           lease_owner = NULL, lease_until = NULL
                     WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                       AND lease_owner = ? AND lease_token = ? AND lease_until >= ?
                    RETURNING id
                ), cleaned AS (
                    DELETE FROM connector_snapshot_manifest snapshot
                     USING failed
                     WHERE snapshot.run_id = failed.id
                    RETURNING snapshot.external_id
                )
                SELECT EXISTS (SELECT 1 FROM failed)
                """,
                Boolean.class,
                requireText(errorCode, "errorCode"),
                databaseTime(completedAt),
                lease.tenantId().value(),
                lease.runId(),
                lease.leaseOwner(),
                lease.leaseToken(),
                databaseTime(completedAt)
        );
        return Boolean.TRUE.equals(failed);
    }

    private SynchronizationLease lease(java.sql.ResultSet result) throws java.sql.SQLException {
        TenantId tenantId = new TenantId(result.getString("tenant_id"));
        return new SynchronizationLease(
                result.getObject("id", UUID.class),
                principal(tenantId, result.getString("principal_json")),
                result.getString("connector_id"),
                result.getObject("snapshot_id", UUID.class),
                new ConnectorCursor(stringMap(result.getString("cursor_json"))),
                result.getLong("records_seen"),
                result.getLong("records_changed"),
                result.getString("lease_owner"),
                result.getLong("lease_token"),
                result.getObject("lease_until", OffsetDateTime.class).toInstant()
        );
    }

    private boolean ownsSnapshotLease(
            SynchronizationLease lease,
            KnowledgeSpaceId spaceId,
            Instant now
    ) {
        Boolean owned = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM connector_sync_run run
                      JOIN connector_instance connector
                        ON connector.tenant_id = run.tenant_id
                       AND connector.id = run.connector_id
                       AND connector.space_id = ?
                     WHERE run.tenant_id = ? AND run.id = ? AND run.status = 'RUNNING'
                       AND run.lease_owner = ? AND run.lease_token = ?
                       AND run.lease_until >= ?
                )
                """,
                Boolean.class,
                spaceId.value(),
                lease.tenantId().value(),
                lease.runId(),
                lease.leaseOwner(),
                lease.leaseToken(),
                databaseTime(now)
        );
        return Boolean.TRUE.equals(owned);
    }

    private Map<String, Object> objectMap(String json) {
        try {
            Map<String, Object> value = jsonMapper.readValue(
                    json,
                    new TypeReference<Map<String, Object>>() { }
            );
            return new LinkedHashMap<>(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "stored connector configuration is invalid",
                    failure
            );
        }
    }

    private Map<String, String> stringMap(String json) {
        Map<String, Object> raw = objectMap(json);
        Map<String, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> values.put(key, String.valueOf(value)));
        return Map.copyOf(values);
    }

    private PrincipalContext principal(TenantId tenantId, String json) {
        Map<String, Object> values = objectMap(json);
        return new PrincipalContext(
                tenantId,
                new PrincipalId(String.valueOf(values.get("principalId"))),
                strings(values.get("roleIds")),
                strings(values.get("departmentIds")),
                Boolean.parseBoolean(String.valueOf(values.get("systemPrincipal")))
        );
    }

    private static Map<String, Object> principal(PrincipalContext principal) {
        return Map.of(
                "principalId", principal.principalId().value(),
                "roleIds", principal.roleIds(),
                "departmentIds", principal.departmentIds(),
                "systemPrincipal", principal.systemPrincipal()
        );
    }

    private static Set<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return Set.of();
        }
        return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String json(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("connector state cannot be serialized", failure);
        }
    }

    private static int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(
                    "stored connector authority is invalid",
                    invalid
            );
        }
    }

    private static void requireCounts(long recordsSeen, long recordsChanged) {
        if (recordsSeen < 0 || recordsChanged < 0 || recordsChanged > recordsSeen) {
            throw new IllegalArgumentException("connector run counts are invalid");
        }
    }

    private static void requireLeaseWindow(Instant leaseUntil, Instant now) {
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
