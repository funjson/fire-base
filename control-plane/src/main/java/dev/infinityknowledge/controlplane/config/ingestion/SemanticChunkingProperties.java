package dev.infinityknowledge.controlplane.config.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 定义语义边界判断参数和同步模型调用硬预算。
 *
 * @param enabled 是否在当前部署安装并开放语义边界 Provider；具体 Space 是否使用由持久化配置决定
 * @param maximumEmbeddingInputs 单文档最大向量化输入数
 * @param maximumInputCharacters 单条语义输入最大字符数
 * @param maximumCandidates 单文档最大候选边界数
 * @param maximumVectorValues 单次结果最大向量标量数
 * @param stageTimeout 语义模型全部请求、重试与排队共用的总时限
 */
@ConfigurationProperties(prefix = "infinity.knowledge.ingestion.semantic-chunking")
public record SemanticChunkingProperties(
        boolean enabled,
        int maximumEmbeddingInputs,
        int maximumInputCharacters,
        int maximumCandidates,
        long maximumVectorValues,
        Duration stageTimeout
) {

    /**
     * 补齐保守默认值，并拒绝会形成无界模型调用的参数。
     */
    public SemanticChunkingProperties {
        maximumEmbeddingInputs = defaultPositive(
                maximumEmbeddingInputs,
                128,
                "semantic maximumEmbeddingInputs must be at least 2",
                2
        );
        maximumInputCharacters = defaultPositive(
                maximumInputCharacters,
                16_000,
                "semantic maximumInputCharacters must be positive",
                1
        );
        maximumCandidates = defaultPositive(
                maximumCandidates,
                127,
                "semantic maximumCandidates must be positive",
                1
        );
        maximumVectorValues = defaultPositive(
                maximumVectorValues,
                262_144L,
                "semantic maximumVectorValues must be positive",
                1L
        );
        stageTimeout = stageTimeout == null ? Duration.ofSeconds(12) : stageTimeout;
        if (stageTimeout.isZero() || stageTimeout.isNegative()
                || stageTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException(
                    "semantic stageTimeout must be positive and no longer than 1 minute"
            );
        }
    }

    private static int defaultPositive(
            int value,
            int fallback,
            String message,
            int minimum
    ) {
        if (value == 0) {
            return fallback;
        }
        if (value < minimum) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static long defaultPositive(
            long value,
            long fallback,
            String message,
            long minimum
    ) {
        if (value == 0L) {
            return fallback;
        }
        if (value < minimum) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
