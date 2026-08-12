package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.spi.connector.ConnectorStateStore;

import java.time.Instant;
import java.util.UUID;

/**
 * 连接器同步任务的稳定状态响应。
 */
public record ConnectorRunResponse(
        UUID runId,
        String connectorId,
        String status,
        long recordsSeen,
        long recordsChanged,
        long recordsDeleted,
        String errorCode,
        Instant startedAt,
        Instant completedAt
) {
    static ConnectorRunResponse from(
            ConnectorStateStore.SynchronizationStatus status
    ) {
        return new ConnectorRunResponse(
                status.runId(),
                status.connectorId(),
                status.status(),
                status.recordsSeen(),
                status.recordsChanged(),
                status.recordsDeleted(),
                status.errorCode(),
                status.startedAt(),
                status.completedAt()
        );
    }
}
