package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
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
import java.util.Objects;
import java.util.Optional;
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
        int affected = jdbc.update("""
                INSERT INTO connector_instance
                    (tenant_id, id, space_id, connector_type, display_name,
                     config_json, status, created_at, updated_at)
                SELECT ?, ?, s.id, ?, ?, ?::jsonb, 'ACTIVE', ?, ?
                  FROM knowledge_space s
                 WHERE s.tenant_id = ? AND s.id = ? AND s.status = 'ACTIVE'
                ON CONFLICT (tenant_id, id) DO UPDATE
                SET space_id = EXCLUDED.space_id,
                    connector_type = EXCLUDED.connector_type,
                    display_name = EXCLUDED.display_name,
                    config_json = EXCLUDED.config_json,
                    status = 'ACTIVE',
                    version = connector_instance.version + 1,
                    updated_at = EXCLUDED.updated_at
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
                        (id, tenant_id, connector_id, snapshot_id, status, started_at)
                    VALUES (?, ?, ?, ?, 'RUNNING', ?)
                    """,
                    run.runId(),
                    run.tenantId().value(),
                    run.connectorId(),
                    run.snapshotId(),
                    databaseTime(run.startedAt())
            ) > 0;
        } catch (DuplicateKeyException running) {
            return false;
        }
    }

    @Override
    public Optional<SynchronizationStatus> findRun(TenantId tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        SynchronizationStatus status = jdbc.query("""
                SELECT id, connector_id, status, records_seen, records_changed,
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
                    result.getString("error_code"),
                    result.getObject("started_at", OffsetDateTime.class).toInstant(),
                    completedAt == null ? null : completedAt.toInstant()
            );
        }, tenantId.value(), runId);
        return Optional.ofNullable(status);
    }

    @Override
    public void saveCheckpoint(ConnectorCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        jdbc.update("""
                INSERT INTO connector_checkpoint
                    (tenant_id, connector_id, cursor_json, snapshot_id, version, updated_at)
                VALUES (?, ?, ?::jsonb, ?, 0, ?)
                ON CONFLICT (tenant_id, connector_id) DO UPDATE
                SET cursor_json = EXCLUDED.cursor_json,
                    snapshot_id = EXCLUDED.snapshot_id,
                    version = connector_checkpoint.version + 1,
                    updated_at = EXCLUDED.updated_at
                """,
                checkpoint.tenantId().value(),
                checkpoint.connectorId(),
                json(checkpoint.cursor().values()),
                checkpoint.snapshotId(),
                databaseTime(checkpoint.updatedAt())
        );
    }

    @Override
    public void complete(
            TenantId tenantId,
            UUID runId,
            long recordsSeen,
            long recordsChanged,
            Instant completedAt
    ) {
        requireRunUpdate(tenantId, runId, completedAt);
        if (recordsSeen < 0 || recordsChanged < 0 || recordsChanged > recordsSeen) {
            throw new IllegalArgumentException("connector run counts are invalid");
        }
        jdbc.update("""
                UPDATE connector_sync_run
                   SET status = 'SUCCEEDED', records_seen = ?,
                       records_changed = ?, completed_at = ?
                 WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                """,
                recordsSeen,
                recordsChanged,
                databaseTime(completedAt),
                tenantId.value(),
                runId
        );
    }

    @Override
    public void fail(
            TenantId tenantId,
            UUID runId,
            String errorCode,
            Instant completedAt
    ) {
        requireRunUpdate(tenantId, runId, completedAt);
        jdbc.update("""
                UPDATE connector_sync_run
                   SET status = 'FAILED', error_code = ?, completed_at = ?
                 WHERE tenant_id = ? AND id = ? AND status = 'RUNNING'
                """,
                requireText(errorCode, "errorCode"),
                databaseTime(completedAt),
                tenantId.value(),
                runId
        );
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

    private static void requireRunUpdate(
            TenantId tenantId,
            UUID runId,
            Instant completedAt
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
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
