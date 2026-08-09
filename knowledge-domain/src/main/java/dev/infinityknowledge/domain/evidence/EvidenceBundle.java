package dev.infinityknowledge.domain.evidence;

import dev.infinityknowledge.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示知识运行时返回给 Agent 的完整证据包。
 *
 * @param requestId 原查询标识
 * @param traceId 检索 Trace 标识
 * @param tenantId 租户标识
 * @param evidences 有序证据
 * @param sufficient 证据是否达到最低充分性阈值
 * @param warnings 冲突、降级或数据新鲜度警告
 * @param generatedAt 生成时间
 */
public record EvidenceBundle(
        UUID requestId,
        UUID traceId,
        TenantId tenantId,
        List<Evidence> evidences,
        boolean sufficient,
        List<String> warnings,
        Instant generatedAt
) {

    /**
     * 校验证据包的请求关联和租户归属。
     */
    public EvidenceBundle {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        evidences = List.copyOf(Objects.requireNonNull(evidences, "evidences must not be null"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings must not be null"));
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
    }
}

