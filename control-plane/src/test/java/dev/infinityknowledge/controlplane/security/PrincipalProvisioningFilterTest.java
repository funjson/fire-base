package dev.infinityknowledge.controlplane.security;

import dev.infinityknowledge.controlplane.application.governance.KnowledgeGovernanceService;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.governance.KnowledgeGovernanceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证认证后主体登记和缺失租户声明的 401 行为。
 */
class PrincipalProvisioningFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registersAuthenticatedPrincipalBeforeContinuing() throws Exception {
        var store = new RecordingGovernanceStore();
        var filter = filter(store);
        authenticate(jwt("tenant-a"));
        var chain = new MockFilterChain();
        var request = new MockHttpServletRequest("GET", "/api/v1/spaces/accessible");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        assertNotNull(store.principal);
        assertEquals("tenant-a", store.principal.tenantId().value());
    }

    @Test
    void rejectsTokenWithoutTenantClaim() throws Exception {
        var store = new RecordingGovernanceStore();
        var filter = filter(store);
        authenticate(jwt(null));
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/knowledge/query"),
                response,
                chain
        );

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
        assertNull(store.principal);
    }

    private PrincipalProvisioningFilter filter(RecordingGovernanceStore store) {
        return new PrincipalProvisioningFilter(
                new JwtPrincipalContextFactory(),
                new KnowledgeGovernanceService(
                        store,
                        Clock.fixed(
                                Instant.parse("2026-08-03T00:00:00Z"),
                                ZoneOffset.UTC
                        )
                )
        );
    }

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of())
        );
    }

    private Jwt jwt(String tenantId) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.parse("2026-08-03T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-03T01:00:00Z"));
        if (tenantId != null) {
            builder.claim("tenant_id", tenantId);
        }
        return builder.build();
    }

    private static final class RecordingGovernanceStore
            implements KnowledgeGovernanceStore {
        private PrincipalContext principal;

        @Override
        public void ensurePrincipal(PrincipalContext principal, Instant now) {
            this.principal = principal;
        }

        @Override
        public CreateSpaceResult createSpace(
                PrincipalContext principal,
                KnowledgeSpaceId spaceId,
                String name,
                String description,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AccessibleSpace> accessibleSpaces(PrincipalContext principal) {
            return List.of();
        }

        @Override
        public List<SpaceGrant> grants(TenantId tenantId, KnowledgeSpaceId spaceId) {
            return List.of();
        }

        @Override
        public void grant(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                SpaceGrant grant,
                PrincipalId grantedBy,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean revoke(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                SpaceGrant grant
        ) {
            return false;
        }
    }
}
