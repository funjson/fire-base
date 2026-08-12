package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the optional embedding-based reranker resource budget.
 *
 * @param enabled whether the real reranker is enabled
 * @param maxCandidates maximum candidates embedded per request
 * @param maxQueryCharacters maximum query characters embedded per request
 * @param maxCandidateCharacters maximum characters embedded for one candidate
 * @param maxTotalCharacters maximum characters embedded for the whole rerank request
 */
@ConfigurationProperties(prefix = "infinity.knowledge.retrieval.reranker")
public record RerankerProperties(
        boolean enabled,
        int maxCandidates,
        int maxQueryCharacters,
        int maxCandidateCharacters,
        int maxTotalCharacters
) {

    /**
     * Applies conservative defaults and rejects unbounded settings.
     */
    public RerankerProperties {
        if (maxCandidates < 1) {
            maxCandidates = 24;
        }
        if (maxQueryCharacters < 1) {
            maxQueryCharacters = 4_096;
        }
        if (maxCandidateCharacters < 1) {
            maxCandidateCharacters = 8_000;
        }
        if (maxTotalCharacters < 1) {
            maxTotalCharacters = 100_000;
        }
        if (maxCandidates > 256) {
            throw new IllegalArgumentException(
                    "reranker maxCandidates must not exceed 256"
            );
        }
        if (maxQueryCharacters > 32_768) {
            throw new IllegalArgumentException(
                    "reranker maxQueryCharacters must not exceed 32768"
            );
        }
        if (maxCandidateCharacters > 100_000) {
            throw new IllegalArgumentException(
                    "reranker maxCandidateCharacters must not exceed 100000"
            );
        }
        if (maxTotalCharacters <= maxQueryCharacters
                || maxTotalCharacters > 1_000_000) {
            throw new IllegalArgumentException(
                    "reranker maxTotalCharacters must exceed maxQueryCharacters "
                            + "and be at most 1000000"
            );
        }
    }
}
