package dev.infinityknowledge.controlplane.api;

import java.util.UUID;

/**
 * 返回异步连接器同步运行标识和当前状态。
 */
public record ConnectorSyncResponse(UUID runId, String status) {
}
