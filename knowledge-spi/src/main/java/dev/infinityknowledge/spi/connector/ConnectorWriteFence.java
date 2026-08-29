package dev.infinityknowledge.spi.connector;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;

import java.util.Objects;
import java.util.UUID;

/**标识在知识修订（knowledge revision）提交时必须继续持有的连接器租约。
 *
 * <p>PostgreSQL 写入器会在与文档写入相同的事务中验证并锁定该租约。仅在摄入前发送心跳并不能构成提交屏障（commit fence）。
 */
public record ConnectorWriteFence(
        TenantId tenantId,
        UUID runId,
        String leaseOwner,
        long leaseToken
) {
    public ConnectorWriteFence {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        leaseOwner = DomainChecks.requiredText(leaseOwner, "leaseOwner", 128);
        if (leaseToken < 1) {
            throw new IllegalArgumentException("leaseToken must be positive");
        }
    }

    /** 创建由已获取的同步租约所携带的不可变写入屏障。 */
    public static ConnectorWriteFence from(
            ConnectorStateStore.SynchronizationLease lease
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        return new ConnectorWriteFence(
                lease.tenantId(), lease.runId(), lease.leaseOwner(), lease.leaseToken()
        );
    }
}
