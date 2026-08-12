package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.domain.audit.AuditOutcome;
import dev.infinityknowledge.domain.audit.MutationAuditEvent;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.audit.AuditPage;
import dev.infinityknowledge.spi.audit.AuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditApplicationServiceTest {

    @Test
    void bindsEveryReadToAuthenticatedTenant() {
        RecordingStore store = new RecordingStore();
        AuditApplicationService service = new AuditApplicationService(store);
        PrincipalContext admin = new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin-1"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );

        var page = service.find(admin, 25, 5);

        assertEquals(new TenantId("tenant-a"), store.requestedTenant);
        assertEquals(25, store.requestedLimit);
        assertEquals(5, store.requestedOffset);
        assertEquals("tenant-a", page.items().getFirst().tenantId());
    }

    @Test
    void rejectsNonAdministratorAndUnboundedPages() {
        AuditApplicationService service = new AuditApplicationService(new RecordingStore());
        PrincipalContext reader = new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("reader-1"),
                Set.of(),
                Set.of(),
                false
        );
        PrincipalContext admin = new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin-1"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );

        assertThrows(AccessDeniedException.class, () -> service.find(reader, 50, 0));
        assertThrows(IllegalArgumentException.class, () -> service.find(admin, 201, 0));
        assertThrows(IllegalArgumentException.class, () -> service.find(admin, 50, -1));
    }

    private static final class RecordingStore implements AuditStore {
        private TenantId requestedTenant;
        private int requestedLimit;
        private int requestedOffset;

        @Override
        public void append(MutationAuditEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuditPage find(TenantId tenantId, int limit, int offset) {
            requestedTenant = tenantId;
            requestedLimit = limit;
            requestedOffset = offset;
            MutationAuditEvent event = new MutationAuditEvent(
                    UUID.randomUUID(),
                    tenantId,
                    new PrincipalId("user-1"),
                    UUID.randomUUID(),
                    "POST",
                    "/api/v1/documents/markdown",
                    "POST /api/v1/documents/markdown",
                    200,
                    AuditOutcome.SUCCEEDED,
                    Duration.ofMillis(5),
                    Instant.parse("2026-08-11T00:00:00Z")
            );
            return new AuditPage(List.of(event), limit, offset, 1);
        }
    }
}
