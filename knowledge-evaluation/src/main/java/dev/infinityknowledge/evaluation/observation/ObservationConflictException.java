package dev.infinityknowledge.evaluation.observation;

/**
 * 同一 eventId、executionId 或 sequence 被复用于不同事实时抛出的冲突。
 */
public final class ObservationConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 创建不包含原查询或知识正文的稳定冲突。 */
    public ObservationConflictException(String message) {
        super(message);
    }
}
