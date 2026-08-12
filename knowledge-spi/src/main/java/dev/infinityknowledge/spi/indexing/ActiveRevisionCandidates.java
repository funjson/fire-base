package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;

import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Loads a bounded number of extra external-index hits before applying the
 * transactional active-revision guard.
 *
 * <p>External indexes retain immutable revisions, so stale revisions can
 * otherwise occupy the complete channel window. Searches are retried with at
 * most 1x, 2x and 4x the requested candidate count. Only guard-approved
 * candidates leave this helper.</p>
 */
public final class ActiveRevisionCandidates {

    private static final int MAX_OVERFETCH_MULTIPLIER = 4;

    private ActiveRevisionCandidates() {
    }

    /**
     * Searches, filters and finally truncates one retrieval channel.
     *
     * @param tenantId authoritative query tenant
     * @param candidateLimit maximum number of returned active candidates
     * @param search backend search that must retain the original access predicates
     * @param guard active-revision authority
     * @return active candidates in backend relevance order with contiguous ranks
     */
    public static List<RetrievalCandidate> load(
            TenantId tenantId,
            int candidateLimit,
            IntFunction<List<RetrievalCandidate>> search,
            ActiveRevisionGuard guard
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(search, "search must not be null");
        Objects.requireNonNull(guard, "guard must not be null");
        if (candidateLimit < 1 || candidateLimit > 1_000) {
            throw new IllegalArgumentException("candidateLimit must be between 1 and 1000");
        }

        int maximumSearchLimit = candidateLimit * MAX_OVERFETCH_MULTIPLIER;
        int searchLimit = candidateLimit;
        List<RetrievalCandidate> active;
        while (true) {
            List<RetrievalCandidate> raw = List.copyOf(
                    Objects.requireNonNull(
                            search.apply(searchLimit),
                            "backend search result must not be null"
                    )
            ).stream().limit(searchLimit).toList();
            active = guard.retainActive(tenantId, raw);
            if (active.size() >= candidateLimit
                    || raw.size() < searchLimit
                    || searchLimit >= maximumSearchLimit) {
                break;
            }
            searchLimit = Math.min(maximumSearchLimit, searchLimit * 2);
        }

        return rerank(active.stream().limit(candidateLimit).toList());
    }

    private static List<RetrievalCandidate> rerank(List<RetrievalCandidate> candidates) {
        java.util.ArrayList<RetrievalCandidate> reranked = new java.util.ArrayList<>(
                candidates.size()
        );
        for (int index = 0; index < candidates.size(); index++) {
            RetrievalCandidate candidate = candidates.get(index);
            reranked.add(new RetrievalCandidate(
                    candidate.chunkId(),
                    candidate.tenantId(),
                    candidate.spaceId(),
                    candidate.documentId(),
                    candidate.revisionId(),
                    candidate.channel(),
                    index + 1,
                    candidate.score(),
                    candidate.title(),
                    candidate.sectionPath(),
                    candidate.content(),
                    candidate.sourceUri(),
                    candidate.metadata()
            ));
        }
        return List.copyOf(reranked);
    }
}
