package dev.infinityknowledge.controlplane.api.connector;

import dev.infinityknowledge.controlplane.application.connector.ConnectorApplicationService;
import dev.infinityknowledge.controlplane.security.JwtPrincipalContextFactory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 外部知识源配置和同步控制 API。
 */
@RestController
@RequestMapping("/api/v1/connectors")
public final class ConnectorController {
    private final ConnectorApplicationService connectors;
    private final JwtPrincipalContextFactory principals;

    public ConnectorController(
            ConnectorApplicationService connectors,
            JwtPrincipalContextFactory principals
    ) {
        this.connectors = connectors;
        this.principals = principals;
    }

    @PostMapping("/obsidian")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configureObsidian(
            @Valid @RequestBody ObsidianConnectorRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        connectors.configureObsidian(principals.create(jwt), request);
    }

    @PostMapping("/{connectorId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ConnectorSyncResponse synchronize(
            @PathVariable String connectorId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return connectors.synchronize(principals.create(jwt), connectorId);
    }

    @GetMapping("/runs/{runId}")
    public ConnectorRunResponse synchronizationRun(
            @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ConnectorRunResponse.from(
                connectors.synchronizationRun(principals.create(jwt), runId)
        );
    }
}
