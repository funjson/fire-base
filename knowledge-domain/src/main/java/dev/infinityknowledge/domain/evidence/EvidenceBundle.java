package dev.infinityknowledge.domain.evidence;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalStopReason;
import dev.infinityknowledge.domain.retrieval.RetrievalTerminalStatus;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

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
 * @param terminalStatus 证据充分性与技术协议终态
 * @param stopReason 终态停止原因
 * @param degraded 是否发生非预期回退
 * @param visitedSpaceIds 实际进入过的有序 Space
 * @param configurationFingerprints 各访问 Space 实际执行的配置指纹
 * @param warnings 冲突、降级或数据新鲜度警告
 * @param generatedAt 生成时间
 */
public record EvidenceBundle(
        UUID requestId,
        UUID traceId,
        TenantId tenantId,
        List<Evidence> evidences,
        RetrievalTerminalStatus terminalStatus,
        RetrievalStopReason stopReason,
        boolean degraded,
        List<KnowledgeSpaceId> visitedSpaceIds,
        List<String> configurationFingerprints,
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
        Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        visitedSpaceIds = List.copyOf(Objects.requireNonNull(
                visitedSpaceIds,
                "visitedSpaceIds must not be null"
        ));
        configurationFingerprints = List.copyOf(Objects.requireNonNull(
                configurationFingerprints,
                "configurationFingerprints must not be null"
        ));
        if (visitedSpaceIds.isEmpty()
                || visitedSpaceIds.size() != configurationFingerprints.size()
                || new java.util.HashSet<>(visitedSpaceIds).size() != visitedSpaceIds.size()
                || configurationFingerprints.stream().anyMatch(value ->
                value == null || !value.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "visited spaces and configuration fingerprints must be aligned and valid"
            );
        }
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings must not be null"));
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
    }

    /** 返回是否经过 Coverage 判断并达到阈值。 */
    public boolean sufficient() {
        return terminalStatus == RetrievalTerminalStatus.SUFFICIENT;
    }

}
