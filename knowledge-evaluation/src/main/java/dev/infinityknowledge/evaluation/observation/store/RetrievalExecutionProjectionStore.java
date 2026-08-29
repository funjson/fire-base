package dev.infinityknowledge.evaluation.observation.store;

import dev.infinityknowledge.evaluation.observation.RetrievalExecutionObservation;

/**
 * 保存按 execution 聚合的检索观测投影。
 *
 * <p>投影可以被原始事件完整重建，不承担事件事实源职责。实现不得用较旧、事件数更少
 * 的快照覆盖较新的快照。</p>
 */
@FunctionalInterface
public interface RetrievalExecutionProjectionStore {

    /** 幂等写入当前 execution 快照。 */
    void upsert(RetrievalExecutionObservation execution);
}
