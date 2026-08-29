package dev.infinityknowledge.domain.retrieval.observation;

/**
 * 标识一次检索执行的用途，防止离线评测和测试流量污染在线指标。
 */
public enum RetrievalObservationPurpose {
    ONLINE,
    EVALUATION,
    TEST_PLAZA,
    SHADOW,
    REPLAY
}
