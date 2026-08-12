package dev.infinityknowledge.spi.audit;

import dev.infinityknowledge.domain.audit.MutationAuditEvent;

import java.util.List;
import java.util.Objects;

/** Tenant-scoped page of mutation audit events. */
public record AuditPage(
        List<MutationAuditEvent> items,
        int limit,
        int offset,
        long total
) {
    public AuditPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (limit < 1 || offset < 0 || total < 0) {
            throw new IllegalArgumentException("invalid audit page bounds");
        }
    }
}
