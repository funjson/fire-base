package dev.infinityknowledge.controlplane.security;

import dev.infinityknowledge.controlplane.api.RequestCorrelationFilter;
import dev.infinityknowledge.domain.audit.MutationAuditEvent;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.audit.AuditPage;
import dev.infinityknowledge.spi.audit.AuditStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MutationAuditFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsOnlySafeNormalizedMetadataForMutation() throws Exception {
        RecordingAuditStore store = new RecordingAuditStore();
        MutationAuditFilter filter = filter(store);
        authenticate();
        UUID requestId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PATCH",
                "/api/v1/documents/sensitive-document-id?token=secret-query"
        );
        request.setRequestURI("/api/v1/documents/sensitive-document-id");
        request.setQueryString("token=secret-query");
        request.addHeader("Authorization", "Bearer secret-token");
        request.setContent("secret chunk body".getBytes(StandardCharsets.UTF_8));
        request.setAttribute(RequestCorrelationFilter.ATTRIBUTE, requestId);
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/documents/{documentId}"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(204)
        );

        assertEquals(1, store.events.size());
        MutationAuditEvent event = store.events.getFirst();
        assertEquals("tenant-a", event.tenantId().value());
        assertEquals("user-1", event.principalId().value());
        assertEquals(requestId, event.requestId());
        assertEquals("/api/v1/documents/{documentId}", event.routePattern());
        assertEquals("PATCH /api/v1/documents/{documentId}", event.action());
        assertEquals(204, event.responseStatus());
        String safeProjection = event.toString();
        assertFalse(safeProjection.contains("sensitive-document-id"));
        assertFalse(safeProjection.contains("secret-query"));
        assertFalse(safeProjection.contains("secret-token"));
        assertFalse(safeProjection.contains("secret chunk body"));
    }

    @Test
    void ignoresReadOnlyRequests() throws Exception {
        RecordingAuditStore store = new RecordingAuditStore();
        MutationAuditFilter filter = filter(store);
        authenticate();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/admin/audit-events"),
                new MockHttpServletResponse(),
                new MockFilterChain()
        );

        assertEquals(0, store.events.size());
    }

    @Test
    void ignoresReadSemanticPostRequests() throws Exception {
        RecordingAuditStore store = new RecordingAuditStore();
        MutationAuditFilter filter = filter(store);
        authenticate();

        for (String path : List.of(
                "/api/v1/knowledge/query",
                "/api/v1/admin/graph/search",
                "/api/v1/evaluations/datasets/dataset-a/compare"
        )) {
            filter.doFilter(
                    new MockHttpServletRequest("POST", path),
                    new MockHttpServletResponse(),
                    new MockFilterChain()
            );
        }

        assertEquals(0, store.events.size());
    }

    private MutationAuditFilter filter(AuditStore store) {
        return new MutationAuditFilter(
                store,
                new JwtPrincipalContextFactory(),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private void authenticate() {
        Jwt jwt = Jwt.withTokenValue("token-not-persisted")
                .header("alg", "none")
                .subject("user-1")
                .claim("tenant_id", "tenant-a")
                .issuedAt(Instant.parse("2026-08-11T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-11T01:00:00Z"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of())
        );
    }

    private static final class RecordingAuditStore implements AuditStore {
        private final List<MutationAuditEvent> events = new ArrayList<>();

        @Override
        public void append(MutationAuditEvent event) {
            events.add(event);
        }

        @Override
        public AuditPage find(TenantId tenantId, int limit, int offset) {
            throw new UnsupportedOperationException();
        }
    }
}
