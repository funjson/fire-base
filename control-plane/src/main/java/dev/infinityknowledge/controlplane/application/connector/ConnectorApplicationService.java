package dev.infinityknowledge.controlplane.application.connector;

import dev.infinityknowledge.controlplane.api.connector.ConnectorSyncResponse;
import dev.infinityknowledge.controlplane.api.connector.ObsidianConnectorRequest;
import dev.infinityknowledge.controlplane.application.common.OperationInProgressException;
import dev.infinityknowledge.controlplane.application.common.WorkQueueSaturatedException;
import dev.infinityknowledge.controlplane.config.ConnectorProperties;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 配置外部知识源并编排其有界异步扫描。
 */
@Service
public class ConnectorApplicationService {
    private static final String OBSIDIAN = "OBSIDIAN";

    private final ConnectorStateStore state;
    private final ConnectorProperties properties;
    private final Clock clock;
    private final ConnectorRunCoordinator coordinator;

    public ConnectorApplicationService(
            ConnectorStateStore state,
            ConnectorProperties properties,
            Clock clock,
            ConnectorRunCoordinator coordinator
    ) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "coordinator must not be null"
        );
    }

    /**
     * 为当前租户创建或更新 Obsidian Connector。
     */
    public void configureObsidian(
            PrincipalContext principal,
            ObsidianConnectorRequest request
    ) {
        requireAdmin(principal);
        Path vaultRoot = allowedVaultRoot(request.vaultPath());
        if (!Files.isDirectory(vaultRoot)) {
            throw new IllegalArgumentException("vaultPath must be an existing directory");
        }
        boolean configured = state.configure(
                new ConnectorStateStore.ConnectorRegistration(
                        principal.tenantId(),
                        request.connectorId(),
                        new KnowledgeSpaceId(request.spaceId()),
                        OBSIDIAN,
                        request.displayName(),
                        request.authority(),
                        Map.of(
                                "vaultName", request.vaultName(),
                                "vaultPath", vaultRoot.toString()
                        ),
                        clock.instant()
                )
        );
        if (!configured) {
            throw new IllegalArgumentException("active knowledge space does not exist");
        }
    }

    /**
     * 发起一次异步同步运行。
     */
    public ConnectorSyncResponse synchronize(
            PrincipalContext principal,
            String connectorId
    ) {
        requireAdmin(principal);
        SourceConnectorDefinition definition = state.findActive(
                        principal.tenantId(),
                        connectorId
                )
                .orElseThrow(() -> new IllegalArgumentException(
                        "active connector does not exist"
                ));
        if (!coordinator.supports(definition.type())) {
            throw new IllegalStateException(
                    "no source connector provider is registered for type " + definition.type()
            );
        }
        UUID runId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        boolean started = state.tryStart(new ConnectorStateStore.SynchronizationRun(
                runId,
                principal,
                definition.connectorId(),
                snapshotId,
                clock.instant()
        ));
        if (!started) {
            throw new OperationInProgressException(
                    "connector synchronization is already running"
            );
        }
        if (!coordinator.submit(principal.tenantId(), runId)) {
            throw new WorkQueueSaturatedException(
                    "connector synchronization queue is full"
            );
        }
        return new ConnectorSyncResponse(runId, "RUNNING");
    }

    /**
     * 返回当前租户拥有的一次同步运行。
     */
    public ConnectorStateStore.SynchronizationStatus synchronizationRun(
            PrincipalContext principal,
            UUID runId
    ) {
        requireAdmin(principal);
        return state.findRun(principal.tenantId(), runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "connector synchronization run does not exist"
                ));
    }

    private Path allowedVaultRoot(String rawPath) {
        try {
            Path candidate = Path.of(rawPath).toRealPath();
            boolean allowed = properties.normalizedAllowedRoots().stream()
                    .map(this::realAllowedRoot)
                    .anyMatch(candidate::startsWith);
            if (!allowed) {
                throw new AccessDeniedException(
                        "vaultPath is outside configured Obsidian roots"
                );
            }
            return candidate;
        } catch (IOException invalidPath) {
            throw new IllegalArgumentException(
                    "vaultPath must be an existing readable directory",
                    invalidPath
            );
        }
    }

    private Path realAllowedRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException invalidRoot) {
            throw new IllegalStateException(
                    "configured Obsidian root does not exist or is unreadable",
                    invalidRoot
            );
        }
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal()
                && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
