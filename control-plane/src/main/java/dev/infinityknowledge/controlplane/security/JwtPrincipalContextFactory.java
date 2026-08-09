package dev.infinityknowledge.controlplane.security;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Arrays;

/**
 * 将已验证 JWT 声明映射为领域主体上下文。
 */
@Component
public final class JwtPrincipalContextFactory {
    private final Set<String> trustedSystemClients;

    /**
     * 创建只接受明确白名单客户端作为系统主体的映射器。
     */
    @Autowired
    public JwtPrincipalContextFactory(
            @Value("${infinity.knowledge.security.trusted-system-clients:}")
            String trustedSystemClients
    ) {
        this.trustedSystemClients = Arrays.stream(trustedSystemClients.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 供无 Spring 的单元测试使用；默认不信任任何系统客户端。
     */
    JwtPrincipalContextFactory() {
        this.trustedSystemClients = Set.of();
    }

    /**
     * 从 `tenant_id`、`sub`、Realm Roles 和 `departments` 声明创建主体。
     *
     * @param jwt 已通过签名、签发者和时间校验的 JWT
     * @return 不可变主体上下文
     */
    public PrincipalContext create(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt must not be null");
        String tenant = jwt.getClaimAsString("tenant_id");
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("JWT is missing tenant_id claim");
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("JWT is missing subject");
        }
        boolean systemPrincipal = Boolean.TRUE.equals(
                jwt.getClaimAsBoolean("system_principal")
        );
        if (systemPrincipal && !trustedSystemClients.contains(clientId(jwt))) {
            throw new IllegalArgumentException(
                    "JWT system_principal claim is not issued to a trusted client"
            );
        }
        return new PrincipalContext(
                new TenantId(tenant),
                new PrincipalId(subject),
                realmRoles(jwt),
                stringListClaim(jwt, "departments"),
                systemPrincipal
        );
    }

    /**
     * 读取 Keycloak `realm_access.roles` 嵌套声明。
     *
     * @param jwt JWT
     * @return Realm Role 集合
     */
    private Set<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return Set.of();
        }
        Object rawRoles = realmAccess.get("roles");
        if (!(rawRoles instanceof List<?> values)) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String role && !role.isBlank()) {
                roles.add(role);
            }
        }
        return Set.copyOf(roles);
    }

    /**
     * 安全读取字符串数组声明，忽略非字符串元素。
     *
     * @param jwt JWT
     * @param claim 声明名称
     * @return 字符串集合
     */
    private Set<String> stringListClaim(Jwt jwt, String claim) {
        List<String> values = jwt.getClaimAsStringList(claim);
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private String clientId(Jwt jwt) {
        String authorizedParty = jwt.getClaimAsString("azp");
        if (authorizedParty != null && !authorizedParty.isBlank()) {
            return authorizedParty;
        }
        String clientId = jwt.getClaimAsString("client_id");
        return clientId == null ? "" : clientId;
    }
}
