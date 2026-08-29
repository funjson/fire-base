package dev.infinityknowledge.controlplane.api.governance;

import java.time.Instant;

/**
 * 空间授权 API 的稳定响应模型。
 */
public final class SpaceGovernanceViews {

    private SpaceGovernanceViews() {
    }

    public record AccessibleSpace(
            String id,
            String name,
            String description,
            String status,
            long version,
            long documentCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record Grant(
            String subjectType,
            String subjectId,
            String permission
    ) {
    }
}
