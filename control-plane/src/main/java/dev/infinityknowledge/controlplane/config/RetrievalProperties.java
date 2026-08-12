package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 绑定检索候选预算、融合和平行执行配置。
 *
 * @param mode 检索通道可用性约束
 * @param candidateMultiplier 每通道候选倍数
 * @param rrfConstant RRF 平滑常数
 * @param sufficientThreshold 证据充分性阈值
 * @param parallelism Retriever 并行线程数
 * @param queueCapacity 并发高峰时允许排队的检索任务数
 * @param requestTimeout 单次知识检索的总超时
 * @param channelTimeout 单个召回通道的超时
 */
@ConfigurationProperties(prefix = "infinity.knowledge.retrieval")
public record RetrievalProperties(
        Mode mode,
        int candidateMultiplier,
        int rrfConstant,
        double sufficientThreshold,
        int parallelism,
        int queueCapacity,
        Duration requestTimeout,
        Duration channelTimeout
) {
    /**
     * 校验检索预算，防止错误配置导致无界资源使用。
     */
    public RetrievalProperties {
        mode = mode == null ? Mode.STANDARD : mode;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(35) : requestTimeout;
        channelTimeout = channelTimeout == null ? Duration.ofSeconds(30) : channelTimeout;
        if (candidateMultiplier < 1 || candidateMultiplier > 20) {
            throw new IllegalArgumentException(
                    "candidateMultiplier must be between 1 and 20"
            );
        }
        if (rrfConstant < 1) {
            throw new IllegalArgumentException("rrfConstant must be positive");
        }
        if (!Double.isFinite(sufficientThreshold)
                || sufficientThreshold < 0.0D
                || sufficientThreshold > 1.0D) {
            throw new IllegalArgumentException(
                    "sufficientThreshold must be between 0 and 1"
            );
        }
        if (parallelism < 1 || parallelism > 64) {
            throw new IllegalArgumentException("parallelism must be between 1 and 64");
        }
        if (queueCapacity < parallelism || queueCapacity > 10_000) {
            throw new IllegalArgumentException(
                    "queueCapacity must be between parallelism and 10000"
            );
        }
        validateTimeout(requestTimeout, "requestTimeout");
        validateTimeout(channelTimeout, "channelTimeout");
        if (channelTimeout.compareTo(requestTimeout) > 0) {
            throw new IllegalArgumentException(
                    "channelTimeout must not be greater than requestTimeout"
            );
        }
    }

    private static void validateTimeout(Duration timeout, String name) {
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(name + " must be between 1ns and 10 minutes");
        }
    }

    /**
     * Defines whether optional retrieval channels may degrade or are mandatory.
     */
    public enum Mode {
        STANDARD,
        HYBRID
    }
}
