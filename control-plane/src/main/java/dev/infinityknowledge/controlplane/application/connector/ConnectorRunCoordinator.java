package dev.infinityknowledge.controlplane.application.connector;

import dev.infinityknowledge.controlplane.config.AsyncRunProperties;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.SourceConnectorDefinition;
import dev.infinityknowledge.spi.connector.SourceConnectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 基于持久化租约领取、分发和恢复 Connector 运行，不依赖进程内任务队列。
 */
@Component
public final class ConnectorRunCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectorRunCoordinator.class);

    private final ConnectorStateStore state;
    private final Map<String, SourceConnectorProvider> providers;
    private final Executor executor;
    private final ConnectorSyncWorker worker;
    private final AsyncRunProperties properties;
    private final Clock clock;
    private final String workerId = "connector:" + UUID.randomUUID();

    public ConnectorRunCoordinator(
            ConnectorStateStore state,
            List<SourceConnectorProvider> providers,
            @Qualifier("connectorExecutor") Executor executor,
            ConnectorSyncWorker worker,
            AsyncRunProperties properties,
            Clock clock
    ) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.providers = providersByType(providers);
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public boolean supports(String type) {
        return providers.containsKey(normalizeType(type));
    }

    /** 领取并提交刚持久化的运行；分布式部署时可能由其他实例先领取。 */
    public boolean submit(TenantId tenantId, UUID runId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Instant now = clock.instant();
        var claimed = state.claim(
                tenantId, runId, workerId,
                now.plus(properties.leaseDuration()), now
        );
        if (claimed.isEmpty()) {
            return true;
        }
        var lease = claimed.orElseThrow();
        boolean submitted = dispatch(lease);
        if (!submitted) {
            worker.fail(lease, "WORK_QUEUE_SATURATED");
        }
        return submitted;
    }

    /** 低频、有界地恢复待处理任务和已过期的执行权；触发频率由 knowledge-jobs 负责。 */
    public void recover() {
        Instant now = clock.instant();
        for (ConnectorStateStore.SynchronizationLease lease : state.claimAvailable(
                workerId,
                properties.recoveryBatchSize(),
                now.plus(properties.leaseDuration()),
                now
        )) {
            dispatch(lease);
        }
    }

    private boolean dispatch(ConnectorStateStore.SynchronizationLease lease) {
        SourceConnectorDefinition definition = state.findActive(
                lease.tenantId(), lease.connectorId()
        ).orElse(null);
        if (definition == null) {
            worker.fail(lease, "CONNECTOR_NOT_ACTIVE");
            return true;
        }
        SourceConnectorProvider provider = providers.get(normalizeType(definition.type()));
        if (provider == null) {
            worker.fail(lease, "CONNECTOR_PROVIDER_UNAVAILABLE");
            return true;
        }
        try {
            executor.execute(() -> worker.synchronize(lease, definition, provider));
            return true;
        } catch (RejectedExecutionException saturated) {
            LOGGER.warn(
                    "Connector executor is saturated; task was not submitted: runId={}, tenantId={}",
                    lease.runId(), lease.tenantId().value()
            );
            return false;
        }
    }

    private static Map<String, SourceConnectorProvider> providersByType(
            List<SourceConnectorProvider> providers
    ) {
        Objects.requireNonNull(providers, "providers must not be null");
        Map<String, SourceConnectorProvider> indexed = new LinkedHashMap<>();
        for (SourceConnectorProvider provider : providers) {
            Objects.requireNonNull(provider, "providers must not contain null");
            String type = normalizeType(provider.type());
            if (indexed.putIfAbsent(type, provider) != null) {
                throw new IllegalStateException(
                        "multiple source connector providers are registered for type " + type
                );
            }
        }
        return Map.copyOf(indexed);
    }

    private static String normalizeType(String type) {
        Objects.requireNonNull(type, "connector type must not be null");
        String normalized = type.strip().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("connector type must not be blank");
        }
        return normalized;
    }
}
