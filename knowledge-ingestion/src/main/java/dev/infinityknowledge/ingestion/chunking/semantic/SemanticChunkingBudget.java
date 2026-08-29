package dev.infinityknowledge.ingestion.chunking.semantic;

import java.time.Duration;

/**
 * 限制一次同步语义切分可消耗的输入规模。
 *
 * @param maximumElements 文档最大元素数
 * @param maximumTotalCharacters 文档元素正文总字符上限
 * @param maximumEmbeddingInputs 单次批量向量化输入数上限
 * @param maximumInputCharacters 单个 ElementSlice 向量化正文字符上限
 * @param maximumCandidates 相邻语义边界候选数上限
 * @param maximumVectorValues 单次结果允许的向量标量总数上限
 * @param stageTimeout 从提交模型任务到得到全部向量的总时限
 */
public record SemanticChunkingBudget(
        int maximumElements,
        int maximumTotalCharacters,
        int maximumEmbeddingInputs,
        int maximumInputCharacters,
        int maximumCandidates,
        long maximumVectorValues,
        Duration stageTimeout
) {

    /**
     * 拒绝无法形成至少一组相邻候选的无效预算。
     */
    public SemanticChunkingBudget {
        if (maximumElements < 2) {
            throw new IllegalArgumentException("maximumElements must be at least 2");
        }
        if (maximumTotalCharacters < 1) {
            throw new IllegalArgumentException("maximumTotalCharacters must be positive");
        }
        if (maximumEmbeddingInputs < 2) {
            throw new IllegalArgumentException("maximumEmbeddingInputs must be at least 2");
        }
        if (maximumInputCharacters < 1) {
            throw new IllegalArgumentException("maximumInputCharacters must be positive");
        }
        if (maximumCandidates < 1) {
            throw new IllegalArgumentException("maximumCandidates must be positive");
        }
        if (maximumVectorValues < 1) {
            throw new IllegalArgumentException("maximumVectorValues must be positive");
        }
        if (stageTimeout == null || stageTimeout.isZero() || stageTimeout.isNegative()) {
            throw new IllegalArgumentException("stageTimeout must be positive");
        }
        if (stageTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("stageTimeout must not exceed 1 minute");
        }
    }

    /**
     * 提供适合同步摄取的保守默认预算，部署侧仍应显式配置。
     */
    public static SemanticChunkingBudget defaults() {
        return new SemanticChunkingBudget(
                20_000,
                10_000_000,
                128,
                16_000,
                127,
                262_144L,
                Duration.ofSeconds(12)
        );
    }

    /**
     * 返回进入处理契约的稳定预算片段。
     */
    String contract() {
        return String.join(
                ",",
                "elements=" + maximumElements,
                "totalChars=" + maximumTotalCharacters,
                "embeddingInputs=" + maximumEmbeddingInputs,
                "inputChars=" + maximumInputCharacters,
                "candidates=" + maximumCandidates,
                "vectorValues=" + maximumVectorValues,
                "timeoutMs=" + stageTimeout.toMillis()
        );
    }
}
