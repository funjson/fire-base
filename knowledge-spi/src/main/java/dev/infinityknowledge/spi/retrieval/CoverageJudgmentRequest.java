package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.retrieval.EvidenceRequirement;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Evidence Coverage Judge 的最小输入。
 *
 * <p>请求不包含充分阈值、剩余预算、后续 Chain、授权信息或任何排序分数。</p>
 */
public record CoverageJudgmentRequest(
        String originalQuery,
        String retrievalTarget,
        List<EvidenceRequirement> evidenceRequirements,
        List<CoverageCandidate> candidates,
        int maximumRetainedCandidates
) {
    /** 校验模型工作集与保留窗口不会随反馈轮次无限增长。 */
    public CoverageJudgmentRequest {
        originalQuery = DomainChecks.requiredText(originalQuery, "originalQuery", 16_000);
        retrievalTarget = retrievalTarget == null ? "" : retrievalTarget.strip();
        if (retrievalTarget.length() > 4_000) {
            throw new IllegalArgumentException("retrievalTarget must not exceed 4000 characters");
        }
        evidenceRequirements = List.copyOf(Objects.requireNonNull(
                evidenceRequirements,
                "evidenceRequirements must not be null"
        ));
        if (evidenceRequirements.isEmpty() || evidenceRequirements.size() > 32
                || evidenceRequirements.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "evidenceRequirements must contain between 1 and 32 values"
            );
        }
        candidates = List.copyOf(Objects.requireNonNull(
                candidates,
                "candidates must not be null"
        ));
        if (candidates.size() > 200
                || candidates.stream().anyMatch(Objects::isNull)
                || new HashSet<>(candidates.stream().map(CoverageCandidate::candidateId).toList())
                .size() != candidates.size()) {
            throw new IllegalArgumentException(
                    "candidates must contain at most 200 unique values"
            );
        }
        if (maximumRetainedCandidates < 1 || maximumRetainedCandidates > 100) {
            throw new IllegalArgumentException(
                    "maximumRetainedCandidates must be between 1 and 100"
            );
        }
    }
}
