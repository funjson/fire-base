package dev.infinityknowledge.evaluation;

import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.domain.evidence.EvidenceBundle;
import dev.infinityknowledge.spi.KnowledgeGateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Executes reproducible offline retrieval metrics against the public Knowledge Gateway.
 */
public final class RetrievalEvaluationRunner {

    private final KnowledgeGateway gateway;

    /**
     * Creates the runner.
     */
    public RetrievalEvaluationRunner(KnowledgeGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
    }

    /**
     * Runs all cases and returns macro-averaged Recall@K, MRR and nDCG@K.
     */
    public RetrievalEvaluationReport run(
            List<RetrievalEvaluationCase> evaluationCases,
            int topK
    ) {
        return run(evaluationCases, topK, () -> { });
    }

    /**
     * Runs all cases while invoking a lease guard immediately before every case.
     * A guard failure aborts the run instead of being recorded as a case failure.
     */
    public RetrievalEvaluationReport run(
            List<RetrievalEvaluationCase> evaluationCases,
            int topK,
            Runnable beforeCase
    ) {
        evaluationCases = List.copyOf(Objects.requireNonNull(
                evaluationCases,
                "evaluationCases must not be null"
        ));
        Objects.requireNonNull(beforeCase, "beforeCase must not be null");
        if (evaluationCases.isEmpty()) {
            throw new IllegalArgumentException("evaluationCases must not be empty");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }
        List<RetrievalCaseResult> results = new ArrayList<>(evaluationCases.size());
        for (RetrievalEvaluationCase evaluationCase : evaluationCases) {
            beforeCase.run();
            results.add(runCase(evaluationCase, topK));
        }
        return new RetrievalEvaluationReport(
                results.size(),
                (int) results.stream().filter(result -> !result.succeeded()).count(),
                topK,
                average(results, result -> result.hit() ? 1.0D : 0.0D),
                average(results, RetrievalCaseResult::recallAtK),
                average(results, RetrievalCaseResult::reciprocalRank),
                average(results, RetrievalCaseResult::ndcgAtK),
                results
        );
    }

    private RetrievalCaseResult runCase(
            RetrievalEvaluationCase evaluationCase,
            int topK
    ) {
        long startedAt = System.nanoTime();
        try {
            EvidenceBundle bundle = gateway.retrieve(evaluationCase.query());
            List<Evidence> evidence = bundle.evidences().stream().limit(topK).toList();
            return evaluate(
                    evaluationCase,
                    bundle.traceId(),
                    evidence,
                    topK,
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException failure) {
            return new RetrievalCaseResult(
                    evaluationCase.id(),
                    null,
                    false,
                    false,
                    0.0D,
                    0.0D,
                    0.0D,
                    0,
                    elapsedMillis(startedAt),
                    stableErrorCode(failure)
            );
        }
    }

    private static RetrievalCaseResult evaluate(
            RetrievalEvaluationCase evaluationCase,
            java.util.UUID traceId,
            List<Evidence> evidence,
            int topK,
            long durationMillis
    ) {
        Set<String> expected = expectedKeys(evaluationCase);
        Set<String> found = new HashSet<>();
        double reciprocalRank = 0.0D;
        double dcg = 0.0D;
        for (int index = 0; index < evidence.size(); index++) {
            String key = evidenceKey(evaluationCase, evidence.get(index));
            if (expected.contains(key)) {
                found.add(key);
                if (reciprocalRank == 0.0D) {
                    reciprocalRank = 1.0D / (index + 1.0D);
                }
                dcg += 1.0D / log2(index + 2.0D);
            }
        }
        int idealHits = Math.min(expected.size(), topK);
        double idealDcg = 0.0D;
        for (int index = 0; index < idealHits; index++) {
            idealDcg += 1.0D / log2(index + 2.0D);
        }
        double recall = (double) found.size() / expected.size();
        double ndcg = idealDcg == 0.0D ? 0.0D : dcg / idealDcg;
        return new RetrievalCaseResult(
                evaluationCase.id(),
                traceId,
                true,
                !found.isEmpty(),
                recall,
                reciprocalRank,
                Math.min(1.0D, ndcg),
                evidence.size(),
                durationMillis,
                null
        );
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String stableErrorCode(RuntimeException failure) {
        String simpleName = failure.getClass().getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            return "EVALUATION_CASE_FAILED";
        }
        return "EVALUATION_" + simpleName
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private static Set<String> expectedKeys(RetrievalEvaluationCase evaluationCase) {
        if (!evaluationCase.expectedChunks().isEmpty()) {
            return evaluationCase.expectedChunks().stream()
                    .map(id -> "chunk:" + id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return evaluationCase.expectedDocuments().stream()
                .map(id -> "document:" + id.value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String evidenceKey(
            RetrievalEvaluationCase evaluationCase,
            Evidence evidence
    ) {
        return evaluationCase.expectedChunks().isEmpty()
                ? "document:" + evidence.citation().documentId().value()
                : "chunk:" + evidence.citation().chunkId();
    }

    private static double average(
            List<RetrievalCaseResult> results,
            java.util.function.ToDoubleFunction<RetrievalCaseResult> metric
    ) {
        return results.stream().mapToDouble(metric).average().orElseThrow();
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0D);
    }
}
