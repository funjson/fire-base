package dev.infinityknowledge.ingestion.chunking;

import java.util.Objects;

/**
 * Chunk 的确定性 Token 大小约束。
 *
 * @param tokenCounter 精确 Tokenizer 或明确标注为非精确的预算估算器
 * @param minimumTokens 软边界两侧尽量达到的最小大小
 * @param targetTokens 常规 Chunk 的目标大小
 * @param maximumTokens 任何 Chunk 都不得突破的硬上限
 * @param overlapTokens 相邻非结构硬边界 Chunk 的最大重叠预算
 */
public record ChunkSizing(
        TokenCounter tokenCounter,
        int minimumTokens,
        int targetTokens,
        int maximumTokens,
        int overlapTokens
) {

    /** 校验大小关系，避免 Planner 和 Assembler 各自猜测非法配置。 */
    public ChunkSizing {
        tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
        if (minimumTokens < 1) {
            throw new IllegalArgumentException("minimumTokens must be positive");
        }
        if (targetTokens < minimumTokens) {
            throw new IllegalArgumentException("targetTokens must not be less than minimumTokens");
        }
        if (maximumTokens < targetTokens) {
            throw new IllegalArgumentException("maximumTokens must not be less than targetTokens");
        }
        if (overlapTokens < 0 || overlapTokens >= minimumTokens) {
            throw new IllegalArgumentException(
                    "overlapTokens must be non-negative and less than minimumTokens"
            );
        }
    }

    /** 返回参与修订身份的完整大小契约。 */
    public String contract() {
        return String.join(
                ":",
                "tokenizer=" + tokenCounter.id(),
                "counter=" + tokenCounter.contract(),
                "minimum=" + minimumTokens,
                "target=" + targetTokens,
                "maximum=" + maximumTokens,
                "overlap=" + overlapTokens
        );
    }
}
