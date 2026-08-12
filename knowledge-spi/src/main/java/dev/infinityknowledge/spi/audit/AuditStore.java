package dev.infinityknowledge.spi.audit;

import dev.infinityknowledge.domain.audit.MutationAuditEvent;
import dev.infinityknowledge.domain.identity.TenantId;

/** Persistence boundary for security-safe mutation audit events. */
public interface AuditStore {

    void append(MutationAuditEvent event);

    AuditPage find(TenantId tenantId, int limit, int offset);
}
