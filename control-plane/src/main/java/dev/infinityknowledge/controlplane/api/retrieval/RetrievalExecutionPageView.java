package dev.infinityknowledge.controlplane.api.retrieval;

import dev.infinityknowledge.evaluation.observation.query.RetrievalExecutionPage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 在线检索执行记录分页 HTTP 视图。 */
public record RetrievalExecutionPageView(
        int page,
        int size,
        long totalItems,
        int totalPages,
        List<ItemView> items
) {
    /** 从已授权安全读模型创建分页视图。 */
    public static RetrievalExecutionPageView from(RetrievalExecutionPage value) {
        return new RetrievalExecutionPageView(
                value.page(),
                value.size(),
                value.totalItems(),
                value.totalPages(),
                value.items().stream().map(ItemView::from).toList()
        );
    }

    /** 不含查询、正文和模型响应的一次 execution 摘要。 */
    public record ItemView(
            UUID requestId,
            UUID executionId,
            Instant startedAt,
            Instant lastObservedAt,
            Instant completedAt,
            Long durationMillis,
            String completeness,
            String technicalStatus,
            String terminalStatus,
            String stopReason,
            Boolean degraded,
            Integer attemptCount,
            Integer resultCount,
            int eventCount,
            List<String> spaceIds,
            List<String> configFingerprints
    ) {
        static ItemView from(RetrievalExecutionPage.Item value) {
            return new ItemView(
                    value.requestId(),
                    value.executionId(),
                    value.startedAt(),
                    value.lastObservedAt(),
                    value.completedAt(),
                    value.durationMillis(),
                    value.completeness(),
                    value.technicalStatus(),
                    value.terminalStatus(),
                    value.stopReason(),
                    value.degraded(),
                    value.attemptCount(),
                    value.resultCount(),
                    value.eventCount(),
                    value.spaceIds(),
                    value.configFingerprints()
            );
        }
    }
}
