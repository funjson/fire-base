package dev.infinityknowledge.controlplane.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 配置无状态 OIDC Resource Server，所有业务 API 必须携带 Bearer JWT。
 */
@Configuration
public class SecurityConfiguration {

    /**
     * 开放存活检查并保护全部业务端点。
     *
     * @param http Spring Security 构建器
     * @return 安全过滤链
     * @throws Exception 安全配置失败
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            PrincipalProvisioningFilter principalProvisioningFilter,
            MutationAuditFilter mutationAuditFilter
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                ))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .addFilterAfter(
                        mutationAuditFilter,
                        BearerTokenAuthenticationFilter.class
                )
                .addFilterAfter(
                        principalProvisioningFilter,
                        MutationAuditFilter.class
                );
        return http.build();
    }

    /** The audit filter belongs to the authenticated security chain, not the servlet chain. */
    @Bean
    FilterRegistrationBean<MutationAuditFilter> mutationAuditFilterRegistration(
            MutationAuditFilter filter
    ) {
        FilterRegistrationBean<MutationAuditFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Allows the separately served management console to call the API.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${infinity.knowledge.web.allowed-origins:"
                    + "http://localhost:5173,http://127.0.0.1:5173}")
            String allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList());
        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Request-Id"
        ));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
