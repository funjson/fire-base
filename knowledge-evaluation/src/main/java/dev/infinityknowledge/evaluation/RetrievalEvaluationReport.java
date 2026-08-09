package dev.infinityknowledge.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Macro-averaged offline retrieval report.
 */
public record RetrievalEvaluationReport(
        int caseCount,
        int failedCaseCount,
        int topK,
        double hitRate,
        double recallAtK,
        double mrr,
        double ndcgAtK,
        List<RetrievalCaseResult> cases
) {

    /**
     * Copies case results and validates counts.
     */
    public RetrievalEvaluationReport {
        if (caseCount < 1 || topK < 1) {
            throw new IllegalArgumentException("caseCount and topK must be positive");
        }
        if (failedCaseCount < 0 || failedCaseCount > caseCount) {
            throw new IllegalArgumentException("failedCaseCount is outside case count");
        }
        cases = List.copyOf(Objects.requireNonNull(cases, "cases must not be null"));
        if (cases.size() != caseCount) {
            throw new IllegalArgumentException("caseCount must equal cases size");
        }
    }
}
