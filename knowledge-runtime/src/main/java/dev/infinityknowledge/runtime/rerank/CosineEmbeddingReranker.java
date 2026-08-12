package dev.infinityknowledge.runtime.rerank;

import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;
import dev.infinityknowledge.spi.retrieval.Reranker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reorders already-authorized candidates by cosine similarity using the configured embedding
 * provider. One provider invocation covers the query and every bounded candidate.
 */
public final class CosineEmbeddingReranker implements Reranker {
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingSpec embeddingSpec;
    private final int maxCandidates;
    private final int maxQueryCharacters;
    private final int maxCandidateCharacters;
    private final int maxTotalCharacters;

    /**
     * Creates a bounded, vendor-neutral embedding reranker.
     *
     * @param embeddingProvider batch embedding provider
     * @param embeddingSpec immutable model contract
     * @param maxCandidates maximum candidates included in one rerank operation
     * @param maxQueryCharacters maximum query characters sent to the provider
     * @param maxCandidateCharacters maximum characters sent for one candidate
     * @param maxTotalCharacters maximum characters sent in the whole provider invocation
     */
    public CosineEmbeddingReranker(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            int maxCandidates,
            int maxQueryCharacters,
            int maxCandidateCharacters,
            int maxTotalCharacters
    ) {
        this.embeddingProvider = Objects.requireNonNull(
                embeddingProvider,
                "embeddingProvider must not be null"
        );
        this.embeddingSpec = Objects.requireNonNull(
                embeddingSpec,
                "embeddingSpec must not be null"
        );
        if (maxCandidates < 1 || maxCandidates > 256) {
            throw new IllegalArgumentException("maxCandidates must be between 1 and 256");
        }
        if (maxQueryCharacters < 1 || maxQueryCharacters > 32_768) {
            throw new IllegalArgumentException(
                    "maxQueryCharacters must be between 1 and 32768"
            );
        }
        if (maxCandidateCharacters < 1 || maxCandidateCharacters > 100_000) {
            throw new IllegalArgumentException(
                    "maxCandidateCharacters must be between 1 and 100000"
            );
        }
        if (maxTotalCharacters <= maxQueryCharacters
                || maxTotalCharacters > 1_000_000) {
            throw new IllegalArgumentException(
                    "maxTotalCharacters must exceed maxQueryCharacters and be at most 1000000"
            );
        }
        this.maxCandidates = maxCandidates;
        this.maxQueryCharacters = maxQueryCharacters;
        this.maxCandidateCharacters = maxCandidateCharacters;
        this.maxTotalCharacters = maxTotalCharacters;
    }

    @Override
    public List<RetrievalCandidate> rerank(
            String query,
            List<RetrievalCandidate> candidates,
            int limit
    ) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievalCandidate> boundedCandidates = candidates.stream()
                .limit(maxCandidates)
                .toList();
        List<String> texts = boundedTexts(query, boundedCandidates);
        int includedCandidates = texts.size() - 1;
        if (includedCandidates < 1) {
            throw new IllegalStateException("reranker character budget excludes all candidates");
        }

        List<EmbeddingVector> vectors = List.copyOf(Objects.requireNonNull(
                embeddingProvider.embed(texts, embeddingSpec),
                "embeddingProvider must not return null"
        ));
        List<List<Double>> orderedVectors = validateAndOrder(vectors, texts.size());
        List<Double> queryVector = orderedVectors.getFirst();
        double queryNorm = norm(queryVector);
        if (queryNorm == 0.0D) {
            throw new IllegalStateException("embedding provider returned a zero query vector");
        }

        List<ScoredCandidate> scored = new ArrayList<>(includedCandidates);
        for (int index = 0; index < includedCandidates; index++) {
            List<Double> candidateVector = orderedVectors.get(index + 1);
            double candidateNorm = norm(candidateVector);
            if (candidateNorm == 0.0D) {
                throw new IllegalStateException(
                        "embedding provider returned a zero candidate vector"
                );
            }
            scored.add(new ScoredCandidate(
                    boundedCandidates.get(index),
                    cosine(queryVector, queryNorm, candidateVector, candidateNorm),
                    index
            ));
        }
        List<RetrievalCandidate> ordered = new ArrayList<>(scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::similarity)
                        .reversed()
                        .thenComparingInt(ScoredCandidate::originalIndex))
                .limit(Math.min(limit, scored.size()))
                .map(ScoredCandidate::candidate)
                .toList());
        int resultLimit = Math.min(limit, candidates.size());
        for (RetrievalCandidate candidate : candidates) {
            if (ordered.size() >= resultLimit) {
                break;
            }
            if (!ordered.contains(candidate)) {
                ordered.add(candidate);
            }
        }
        return List.copyOf(ordered);
    }

    private List<String> boundedTexts(
            String query,
            List<RetrievalCandidate> candidates
    ) {
        String boundedQuery = truncate(query, maxQueryCharacters);
        List<String> texts = new ArrayList<>(candidates.size() + 1);
        texts.add(boundedQuery);
        int usedCharacters = boundedQuery.length();
        for (RetrievalCandidate candidate : candidates) {
            int remaining = maxTotalCharacters - usedCharacters;
            if (remaining < 1) {
                break;
            }
            String candidateText = candidateText(candidate);
            String bounded = truncate(
                    candidateText,
                    Math.min(maxCandidateCharacters, remaining)
            );
            if (bounded.isBlank()) {
                continue;
            }
            texts.add(bounded);
            usedCharacters += bounded.length();
        }
        return List.copyOf(texts);
    }

    private String candidateText(RetrievalCandidate candidate) {
        Objects.requireNonNull(candidate, "candidates must not contain null values");
        String section = String.join(" / ", candidate.sectionPath());
        return candidate.title() + "\n" + section + "\n" + candidate.content();
    }

    private List<List<Double>> validateAndOrder(
            List<EmbeddingVector> vectors,
            int expectedCount
    ) {
        if (vectors.size() != expectedCount) {
            throw new IllegalStateException("embedding result count differs from input");
        }
        Map<Integer, List<Double>> byIndex = new HashMap<>();
        for (EmbeddingVector vector : vectors) {
            Objects.requireNonNull(vector, "embedding result must not contain null values");
            if (vector.index() >= expectedCount
                    || vector.values().size() != embeddingSpec.dimensions()
                    || byIndex.putIfAbsent(vector.index(), vector.values()) != null) {
                throw new IllegalStateException(
                        "embedding result has invalid index or dimensions"
                );
            }
        }
        List<List<Double>> ordered = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            List<Double> values = byIndex.get(index);
            if (values == null) {
                throw new IllegalStateException("embedding result index is missing");
            }
            ordered.add(values);
        }
        return List.copyOf(ordered);
    }

    private double cosine(
            List<Double> left,
            double leftNorm,
            List<Double> right,
            double rightNorm
    ) {
        double dotProduct = 0.0D;
        for (int index = 0; index < left.size(); index++) {
            dotProduct += left.get(index) * right.get(index);
        }
        double similarity = dotProduct / (leftNorm * rightNorm);
        if (!Double.isFinite(similarity)) {
            throw new IllegalStateException("embedding similarity is not finite");
        }
        return similarity;
    }

    private double norm(List<Double> values) {
        double squared = 0.0D;
        for (double value : values) {
            squared += value * value;
        }
        double result = Math.sqrt(squared);
        if (!Double.isFinite(result)) {
            throw new IllegalStateException("embedding vector norm is not finite");
        }
        return result;
    }

    private String truncate(String value, int maximumCharacters) {
        return value.length() <= maximumCharacters
                ? value
                : value.substring(0, maximumCharacters);
    }

    private record ScoredCandidate(
            RetrievalCandidate candidate,
            double similarity,
            int originalIndex
    ) {
    }
}
