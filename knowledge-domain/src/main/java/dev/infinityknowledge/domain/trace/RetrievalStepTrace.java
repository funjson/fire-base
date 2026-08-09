package dev.infinityknowledge.domain.trace;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.time.Duration;
import java.util.Objects;

/**
 * 记录一个不包含知识正文的检索阶段摘要。
 *
 * @param name 阶段名称
 * @param duration 阶段耗时
 * @param inputCount 输入数量
 * @param outputCount 输出数量
 * @param status 稳定状态
 */
public record RetrievalStepTrace(
        String name,
        Duration duration,
        int inputCount,
        int outputCount,
        String status
) {

    /**
     * 校验阶段计数和非负耗时。
     */
    public RetrievalStepTrace {
        name = DomainChecks.requiredText(name, "step name", 64);
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (inputCount < 0 || outputCount < 0) {
            throw new IllegalArgumentException("trace counts must be non-negative");
        }
        status = DomainChecks.requiredText(status, "step status", 32);
    }
}

