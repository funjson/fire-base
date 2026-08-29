package dev.infinityknowledge.evaluation.observation.store;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;

import java.util.List;
import java.util.UUID;

/**
 * 检索原始观测事件的可替换存储端口。
 *
 * <p>实现必须同时保证 tenant + eventId 幂等和 tenant + executionId + sequence 唯一。
 * 相同身份但内容不同属于数据冲突，禁止按重复事件静默忽略。写入型消费者还必须按
 * execution 串行化“追加、重放、投影”过程，MQ 可通过 execution 分区实现。</p>
 */
public interface RetrievalObservationEventStore {

    /** 追加不可变原始事件。 */
    AppendOutcome append(RetrievalObservation observation);

    /** 返回一次 execution 的全部原始事件，结果必须按 sequence 升序。 */
    List<RetrievalObservation> events(TenantId tenantId, UUID executionId);

    /** 追加结果只区分新事件和内容完全相同的重投。 */
    enum AppendOutcome {
        APPENDED,
        ALREADY_PRESENT
    }
}
