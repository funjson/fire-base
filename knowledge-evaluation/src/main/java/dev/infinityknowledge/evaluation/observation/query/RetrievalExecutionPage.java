package dev.infinityknowledge.evaluation.observation.query;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 在线检索执行记录的安全分页结果。 */
public record RetrievalExecutionPage(
        int page,
        int size,
        long totalItems,
        int totalPages,
        List<Item> items
) {
    /** 验证页码、总数及只读列表。 */
    public RetrievalExecutionPage {
        if (page < 0 || size < 1 || totalItems < 0L || totalPages < 0) {
            throw new IllegalArgumentException("retrieval execution page values are invalid");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (items.size() > size) {
            throw new IllegalArgumentException("retrieval execution page exceeds requested size");
        }
    }

    /** 不含查询、候选正文和模型响应的一次执行摘要。 */
    public record Item(
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
        /** 验证时间、计数及低基数身份列表。 */
        public Item {
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(executionId, "executionId must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            Objects.requireNonNull(lastObservedAt, "lastObservedAt must not be null");
            if (lastObservedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("lastObservedAt must not precede startedAt");
            }
            required(completeness, "completeness");
            if (technicalStatus == null
                    && (terminalStatus != null || stopReason != null)) {
                throw new IllegalArgumentException(
                        "terminal business fields require a technical status"
                );
            }
            if ((terminalStatus == null) != (stopReason == null)) {
                throw new IllegalArgumentException(
                        "terminalStatus and stopReason must be present together"
                );
            }
            boolean completed = terminalStatus != null;
            if (completed != (completedAt != null && durationMillis != null)) {
                throw new IllegalArgumentException(
                        "completedAt and durationMillis must only exist for terminal executions"
                );
            }
            if (completed != (degraded != null && attemptCount != null && resultCount != null)) {
                throw new IllegalArgumentException(
                        "terminal execution facts must be present together"
                );
            }
            if (completed && (!completedAt.equals(lastObservedAt)
                    || durationMillis.longValue()
                    != Duration.between(startedAt, completedAt).toMillis())) {
                throw new IllegalArgumentException("execution duration must match timestamps");
            }
            if ((attemptCount != null && attemptCount < 0)
                    || (resultCount != null && resultCount < 0) || eventCount < 1) {
                throw new IllegalArgumentException("execution counts are invalid");
            }
            spaceIds = List.copyOf(Objects.requireNonNull(spaceIds, "spaceIds must not be null"));
            configFingerprints = List.copyOf(Objects.requireNonNull(
                    configFingerprints,
                    "configFingerprints must not be null"
            ));
        }

        private static void required(String value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }
}
