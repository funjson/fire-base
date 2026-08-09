package dev.infinityknowledge.controlplane.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Keycloak JWT 到领域主体的安全映射。
 */
class JwtPrincipalContextFactoryTest {
    private final JwtPrincipalContextFactory factory = new JwtPrincipalContextFactory();

    /**
     * 验证租户、主体、Realm Role 和部门声明被完整映射。
     */
    @Test
    void mapsKeycloakClaims() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.parse("2026-07-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-26T01:00:00Z"))
                .claim("tenant_id", "tenant-a")
                .claim("realm_access", Map.of(
                        "roles",
                        List.of("knowledge-reader", "employee")
                ))
                .claim("departments", List.of("engineering"))
                .build();

        var principal = factory.create(jwt);

        assertEquals("tenant-a", principal.tenantId().value());
        assertEquals("user-1", principal.principalId().value());
        assertEquals(
                java.util.Set.of("knowledge-reader", "employee"),
                principal.roleIds()
        );
        assertEquals(java.util.Set.of("engineering"), principal.departmentIds());
    }

    /**
     * 验证缺少租户声明的 JWT 即使签名有效也不能进入知识 Runtime。
     */
    @Test
    void rejectsMissingTenantClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.parse("2026-07-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-26T01:00:00Z"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> factory.create(jwt));
    }

    @Test
    void rejectsSystemPrincipalFromUntrustedClient() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("service-1")
                .issuedAt(Instant.parse("2026-07-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-26T01:00:00Z"))
                .claim("tenant_id", "tenant-a")
                .claim("system_principal", true)
                .claim("azp", "untrusted-client")
                .build();

        assertThrows(IllegalArgumentException.class, () -> factory.create(jwt));
    }

    @Test
    void acceptsSystemPrincipalFromConfiguredClient() {
        var trustedFactory = new JwtPrincipalContextFactory("trusted-agent");
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("service-1")
                .issuedAt(Instant.parse("2026-07-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-26T01:00:00Z"))
                .claim("tenant_id", "tenant-a")
                .claim("system_principal", true)
                .claim("azp", "trusted-agent")
                .build();

        assertTrue(trustedFactory.create(jwt).systemPrincipal());
    }
}
