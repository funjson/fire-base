package dev.infinityknowledge.runtime.evidence;

import dev.infinityknowledge.domain.evidence.Citation;
import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.runtime.fusion.FusedCandidate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 把融合候选转换为具有稳定引用的 Agent 证据。
 */
public final class DefaultEvidenceBuilder {

    /**
     * 按融合顺序构建证据，并从元数据读取受约束的权威等级。
     *
     * @param candidates 融合候选
     * @return 有序证据
     */
    public List<Evidence> build(List<FusedCandidate> candidates) {
        return candidates.stream().map(candidate -> {
            var source = candidate.representative();
            Citation citation = new Citation(
                    source.documentId(),
                    source.revisionId(),
                    source.chunkId(),
                    source.title(),
                    source.sectionPath(),
                    source.sourceUri()
            );
            return new Evidence(
                    stableEvidenceId(source.chunkId(), source.revisionId()),
                    source.content(),
                    candidate.relevance(),
                    authority(source.metadata().get("authority")),
                    candidate.channels(),
                    citation
            );
        }).toList();
    }

    /**
     * 使用 Chunk 和修订生成可重复的证据标识，方便调用方去重。
     *
     * @param chunkId Chunk 标识
     * @param revisionId 修订标识
     * @return 稳定证据 UUID
     */
    private UUID stableEvidenceId(UUID chunkId, UUID revisionId) {
        String key = chunkId + ":" + revisionId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将元数据中的权威等级限制在零到一百，非法值使用中性默认值。
     *
     * @param raw 原始权威等级
     * @return 安全权威等级
     */
    private int authority(String raw) {
        if (raw == null) {
            return 50;
        }
        try {
            int value = Integer.parseInt(raw);
            return Math.max(0, Math.min(100, value));
        } catch (NumberFormatException ignored) {
            return 50;
        }
    }
}

