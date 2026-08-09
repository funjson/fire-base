package dev.infinityknowledge.domain.trace;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.identity.PrincipalId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 记录一次查询的安全运行元数据，不保存查询或知识正文。
 *
 * @param id Trace 标识
 * @param requestId 请求标识
 * @param tenantId 租户标识
 * @param principalId 主体标识
 * @param queryHash 查询文本指纹
 * @param steps 阶段摘要
 * @param totalDuration 总耗时
 * @param resultCount 证据数量
 * @param createdAt 创建时间
 */
public record RetrievalTrace(
        UUID id,
        UUID requestId,
        TenantId tenantId,
        PrincipalId principalId,
        String queryHash,
        List<RetrievalStepTrace> steps,
        Duration totalDuration,
        int resultCount,
        Instant createdAt
) {

    /**
     * 校验 Trace 关联字段和计数。
     */
    public RetrievalTrace {
        Objects.requireNonNull(id, "trace id must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(principalId, "principalId must not be null");
        queryHash = DomainChecks.requiredText(queryHash, "queryHash", 128);
        steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
        Objects.requireNonNull(totalDuration, "totalDuration must not be null");
        if (totalDuration.isNegative()) {
            throw new IllegalArgumentException("totalDuration must not be negative");
        }
        if (resultCount < 0) {
            throw new IllegalArgumentException("resultCount must be non-negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
