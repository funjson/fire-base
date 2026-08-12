package dev.infinityknowledge.controlplane.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models for the tenant-bound mutation audit API. */
public final class AuditApi {
    private AuditApi() {
    }

    public record Event(
            UUID id,
            String tenantId,
            String principalId,
            UUID requestId,
            String httpMethod,
            String routePattern,
            String action,
            int responseStatus,
            String outcome,
            long durationMs,
            Instant createdAt
    ) {
    }

    public record Page(
            List<Event> items,
            int limit,
            int offset,
            long total
    ) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
