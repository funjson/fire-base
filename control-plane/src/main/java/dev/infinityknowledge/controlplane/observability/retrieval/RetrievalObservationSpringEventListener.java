package dev.infinityknowledge.controlplane.observability.retrieval;

import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.evaluation.observation.RetrievalObservationProcessor;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;

/**
 * 消费进程内 Spring Event，并在单个事务中完成原始事件、执行投影和指标事实写入。
 *
 * <p>监听器保持同步，冲突和持久化失败会显式返回发布边界，不能伪装成成功消费。
 * 未来 MQ 消费者可直接复用 {@link RetrievalObservationProcessor}。</p>
 */
public final class RetrievalObservationSpringEventListener {
    private final RetrievalObservationProcessor processor;
    private final TransactionOperations transaction;

    /** 创建同步事务监听器。 */
    public RetrievalObservationSpringEventListener(
            RetrievalObservationProcessor processor,
            TransactionOperations transaction
    ) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
    }

    /** 消费发布器发送的完整强类型领域事件。 */
    @EventListener
    public void onObservation(RetrievalObservation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        transaction.executeWithoutResult(status -> processor.process(observation));
    }
}
