package dev.infinityknowledge.controlplane.config.model;

import java.time.Duration;
import java.util.Objects;

/** 校验串行外部模型调用的最坏耗时不会耗尽执行层阶段硬超时。 */
public final class ModelStageBudgetValidator {

    private ModelStageBudgetValidator() {
    }

    /**
     * 校验计数和生成请求的超时、重试与退避总预算必须严格小于阶段超时。
     * 相等也拒绝，因为序列化、线程调度和响应解析仍需要少量时间。
     */
    public static void requireFits(
            Duration stageTimeout,
            Duration tokenizerMaximumLatency,
            Duration generationMaximumLatency,
            String componentName
    ) {
        Objects.requireNonNull(stageTimeout, "stageTimeout must not be null");
        Objects.requireNonNull(tokenizerMaximumLatency, "tokenizerMaximumLatency must not be null");
        Objects.requireNonNull(generationMaximumLatency, "generationMaximumLatency must not be null");
        if (stageTimeout.isZero() || stageTimeout.isNegative()
                || tokenizerMaximumLatency.isNegative()
                || generationMaximumLatency.isNegative()) {
            throw new IllegalArgumentException(
                    "model stage timeout must be positive and external latency budgets nonnegative"
            );
        }
        Duration externalCallBudget = tokenizerMaximumLatency.plus(generationMaximumLatency);
        if (externalCallBudget.compareTo(stageTimeout) >= 0) {
            throw new IllegalStateException(
                    componentName
                            + " tokenizer and generation latency budget must be less than stageTimeout"
            );
        }
    }
}
