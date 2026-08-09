package dev.infinityknowledge.runtime.fusion;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;

import java.util.Objects;
import java.util.Set;

/**
 * 保存同一 Chunk 在多个召回通道中的融合结果。
 *
 * @param representative 用于构建证据的代表候选
 * @param channels 命中通道
 * @param relevance 归一化融合相关性
 */
public record FusedCandidate(
        RetrievalCandidate representative,
        Set<RetrievalChannel> channels,
        double relevance
) {

    /**
     * 校验代表候选、通道和归一化评分。
     */
    public FusedCandidate {
        Objects.requireNonNull(representative, "representative must not be null");
        channels = Set.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        relevance = DomainChecks.unitScore(relevance, "relevance");
    }
}

