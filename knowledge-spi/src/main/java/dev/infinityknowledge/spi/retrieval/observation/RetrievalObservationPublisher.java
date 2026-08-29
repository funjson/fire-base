package dev.infinityknowledge.spi.retrieval.observation;

import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;

/**
 * 发布逐层检索执行事实的技术中立端口。
 *
 * <p>实现可以使用进程内事件、MQ 或测试收集器。发布失败不得改变检索业务结果；
 * 调用方必须在业务边界隔离基础设施异常，同时用不含正文的方式记录发布失败。</p>
 */
@FunctionalInterface
public interface RetrievalObservationPublisher {

    /**
     * 发布一条不可变观测；同一事件重投时必须保留 eventId 和完整内容。
     *
     * @param observation 检索观测事件
     */
    void publish(RetrievalObservation observation);

    /**
     * 返回显式关闭观测时使用的空发布器。
     */
    static RetrievalObservationPublisher noop() {
        return ignored -> { };
    }
}
