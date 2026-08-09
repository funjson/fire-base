package dev.infinityknowledge.evaluation;

import java.util.Objects;
import java.util.UUID;

/**
 * Metrics for one retrieval case.
 */
public record RetrievalCaseResult(
        UUID caseId,
        UUID traceId,
        boolean succeeded,
        boolean hit,
        double recallAtK,
        double reciprocalRank,
        double ndcgAtK,
        int resultCount,
        long durationMillis,
        String errorCode
) {

    /**
     * Validates metric ranges.
     */
    public RetrievalCaseResult {
        Objects.requireNonNull(caseId, "caseId must not be null");
        if (succeeded) {
            Objects.requireNonNull(traceId, "traceId must not be null for successful cases");
            if (errorCode != null) {
                throw new IllegalArgumentException(
                        "errorCode must be null for successful cases"
                );
            }
        } else if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("failed cases must contain an errorCode");
        }
        requireUnit(recallAtK, "recallAtK");
        requireUnit(reciprocalRank, "reciprocalRank");
        requireUnit(ndcgAtK, "ndcgAtK");
        if (resultCount < 0) {
            throw new IllegalArgumentException("resultCount must be non-negative");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be non-negative");
        }
    }

    private static void requireUnit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
