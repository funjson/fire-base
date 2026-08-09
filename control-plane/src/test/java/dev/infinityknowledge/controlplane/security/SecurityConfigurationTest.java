package dev.infinityknowledge.controlplane.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证开发控制台允许来源与 Keycloak 本地配置保持一致。
 */
class SecurityConfigurationTest {

    @Test
    void parsesAllConfiguredConsoleOrigins() {
        var source = new SecurityConfiguration().corsConfigurationSource(
                "http://localhost:5173, http://127.0.0.1:5173"
        );
        var configuration = source.getCorsConfiguration(
                new MockHttpServletRequest("OPTIONS", "/api/v1/knowledge/query")
        );

        assertNotNull(configuration);
        assertEquals(
                List.of("http://localhost:5173", "http://127.0.0.1:5173"),
                configuration.getAllowedOrigins()
        );
    }
}
