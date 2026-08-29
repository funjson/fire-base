package dev.infinityknowledge.evaluation.observation;

import dev.infinityknowledge.domain.retrieval.observation.RetrievalObservation;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;

/**
 * 一次 Space visit 实际采用的配置修订。
 *
 * <p>同一 execution 可以在 NEXT_SPACE 后访问多个 Space，因此配置身份必须绑定
 * visit，而不能压缩成 execution 级单值。</p>
 *
 * @param visitIndex 从零开始的 Space 访问索引
 * @param spaceId 实际访问的 Space
 * @param sourceRevision Space 配置的来源修订
 * @param fingerprint 合并请求覆盖后的有效配置指纹
 */
public record VisitedRetrievalConfiguration(
        int visitIndex,
        KnowledgeSpaceId spaceId,
        long sourceRevision,
        String fingerprint
) {
    /** 校验 visit、Space、修订和真实指纹。 */
    public VisitedRetrievalConfiguration {
        if (visitIndex < 0) {
            throw new IllegalArgumentException("visitIndex must not be negative");
        }
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        if (sourceRevision < 1L) {
            throw new IllegalArgumentException("sourceRevision must be positive");
        }
        if (!RetrievalObservation.isResolvedConfigFingerprint(fingerprint)) {
            throw new IllegalArgumentException(
                    "visited configuration fingerprint must be a lowercase SHA-256 value"
            );
        }
    }
}
