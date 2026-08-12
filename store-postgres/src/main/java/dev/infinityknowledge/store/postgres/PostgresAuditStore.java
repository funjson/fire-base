package dev.infinityknowledge.store.postgres;

import dev.infinityknowledge.domain.audit.AuditOutcome;
import dev.infinityknowledge.domain.audit.MutationAuditEvent;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.audit.AuditPage;
import dev.infinityknowledge.spi.audit.AuditStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL adapter for tenant-bound mutation audit persistence. */
public final class PostgresAuditStore implements AuditStore {
    private static final String INSERT = """
            INSERT INTO mutation_audit_event (
                id, tenant_id, principal_id, request_id, http_method, route_pattern,
                action, response_status, outcome, duration_ms, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;
    private final JdbcTemplate jdbc;

    public PostgresAuditStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public void append(MutationAuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        jdbc.update(
                INSERT,
                event.id(),
                event.tenantId().value(),
                event.principalId().value(),
                event.requestId(),
                event.httpMethod(),
                event.routePattern(),
                event.action(),
                event.responseStatus(),
                event.outcome().name(),
                event.duration().toMillis(),
                event.createdAt().atOffset(ZoneOffset.UTC)
        );
    }

    @Override
    public AuditPage find(TenantId tenantId, int limit, int offset) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (limit < 1 || limit > 200 || offset < 0) {
            throw new IllegalArgumentException("invalid audit page bounds");
        }
        String tenant = tenantId.value();
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM mutation_audit_event WHERE tenant_id = ?",
                Long.class,
                tenant
        );
        List<MutationAuditEvent> items = jdbc.query("""
                SELECT id, tenant_id, principal_id, request_id, http_method, route_pattern,
                       action, response_status, outcome, duration_ms, created_at
                  FROM mutation_audit_event
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, (row, number) -> new MutationAuditEvent(
                row.getObject("id", UUID.class),
                new TenantId(row.getString("tenant_id")),
                new PrincipalId(row.getString("principal_id")),
                row.getObject("request_id", UUID.class),
                row.getString("http_method"),
                row.getString("route_pattern"),
                row.getString("action"),
                row.getInt("response_status"),
                AuditOutcome.valueOf(row.getString("outcome")),
                Duration.ofMillis(row.getLong("duration_ms")),
                row.getObject("created_at", OffsetDateTime.class).toInstant()
        ), tenant, limit, offset);
        return new AuditPage(items, limit, offset, total);
    }
}
