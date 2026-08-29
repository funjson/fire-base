package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 保存一次精排的有限输出，避免排序结果与最终证据展示的分数脱节。
 *
 * <p>{@code scoresByChunk} 中的分数只允许在本次查询、同一 Provider 的候选之间比较，
 * 不能跨查询或跨模型当作全局相关性标尺。Adapter 可以通过评分数量显式报告上游只处理了
 * 部分候选，但 Runtime 必须把这种结果整批降级到 RRF 顺序，不能拼接两套不可比分数。</p>
 *
 * @param orderedCandidates 精排后的候选，必须是输入候选的无重复子集
 * @param scoresByChunk 模型实际评分的 Chunk 与本次批次内可比较原始分数
 * @param scoredCandidateCount 模型实际评分并返回的候选数量
 * @param reasonCode 不含动态文本的稳定 Provider 原因码
 */
public record RerankResult(
        List<RetrievalCandidate> orderedCandidates,
        Map<UUID, Double> scoresByChunk,
        int scoredCandidateCount,
        String reasonCode
) {

    /**
     * 校验有序候选、评分覆盖范围和稳定原因码。
     */
    public RerankResult {
        orderedCandidates = List.copyOf(Objects.requireNonNull(
                orderedCandidates,
                "orderedCandidates must not be null"
        ));
        scoresByChunk = Map.copyOf(Objects.requireNonNull(
                scoresByChunk,
                "scoresByChunk must not be null"
        ));
        Set<UUID> orderedChunkIds = new HashSet<>();
        for (RetrievalCandidate candidate : orderedCandidates) {
            Objects.requireNonNull(
                    candidate,
                    "orderedCandidates must not contain null values"
            );
            if (!orderedChunkIds.add(candidate.chunkId())) {
                throw new IllegalArgumentException(
                        "orderedCandidates must not contain duplicate chunk ids"
                );
            }
        }
        if (scoredCandidateCount < 0
                || scoredCandidateCount > orderedCandidates.size()
                || scoredCandidateCount != scoresByChunk.size()) {
            throw new IllegalArgumentException(
                    "scoredCandidateCount must equal the number of scored returned candidates"
            );
        }
        for (Map.Entry<UUID, Double> score : scoresByChunk.entrySet()) {
            Objects.requireNonNull(score.getKey(), "score chunk id must not be null");
            Objects.requireNonNull(score.getValue(), "rerank score must not be null");
            if (!orderedChunkIds.contains(score.getKey())) {
                throw new IllegalArgumentException(
                        "scoresByChunk must only contain returned candidate chunk ids"
                );
            }
            if (!Double.isFinite(score.getValue())) {
                throw new IllegalArgumentException("rerank score must be finite");
            }
        }
        reasonCode = DomainChecks.requiredText(reasonCode, "reasonCode", 32);
        if (!reasonCode.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "reasonCode must contain only upper-case letters, digits and underscores"
            );
        }
    }
}
