package dev.infinityknowledge.spi.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Coverage Judge 的结构化结果；阈值比较由 Runtime 完成。
 */
public record CoverageJudgmentResult(
        double coverage,
        List<String> missingGaps,
        List<UUID> retainedCandidateIds,
        String reasonCode
) {
    /** 校验分值、有界缺口和有序候选标识。 */
    public CoverageJudgmentResult {
        coverage = DomainChecks.unitScore(coverage, "coverage");
        missingGaps = List.copyOf(Objects.requireNonNull(
                missingGaps,
                "missingGaps must not be null"
        ));
        if (missingGaps.size() > 32 || missingGaps.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 2_000)) {
            throw new IllegalArgumentException("missingGaps contains invalid values");
        }
        retainedCandidateIds = List.copyOf(Objects.requireNonNull(
                retainedCandidateIds,
                "retainedCandidateIds must not be null"
        ));
        if (retainedCandidateIds.size() > 100
                || retainedCandidateIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(retainedCandidateIds).size()
                != retainedCandidateIds.size()) {
            throw new IllegalArgumentException(
                    "retainedCandidateIds must contain unique non-null values"
            );
        }
        reasonCode = DomainChecks.requiredText(reasonCode, "reasonCode", 64)
                .toUpperCase(java.util.Locale.ROOT);
        if (!reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("reasonCode must be a stable upper-case code");
        }
    }
}
