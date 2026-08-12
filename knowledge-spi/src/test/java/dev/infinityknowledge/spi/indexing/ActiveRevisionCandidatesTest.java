package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiveRevisionCandidatesTest {

    private static final TenantId TENANT_ID = new TenantId("tenant-a");
    private static final UUID ACTIVE_REVISION = UUID.randomUUID();
    private static final UUID STALE_REVISION = UUID.randomUUID();

    @Test
    void overfetchesWhenStaleRevisionsOccupyTheInitialWindow() {
        List<RetrievalCandidate> indexed = List.of(
                candidate(1, STALE_REVISION),
                candidate(2, STALE_REVISION),
                candidate(3, ACTIVE_REVISION),
                candidate(4, ACTIVE_REVISION)
        );
        List<Integer> requestedLimits = new ArrayList<>();

        List<RetrievalCandidate> result = ActiveRevisionCandidates.load(
                TENANT_ID,
                2,
                limit -> {
                    requestedLimits.add(limit);
                    return indexed.stream().limit(limit).toList();
                },
                retainingActiveRevision()
        );

        assertEquals(List.of(2, 4), requestedLimits);
        assertEquals(
                List.of("3", "4"),
                result.stream().map(value -> value.metadata().get("originalRank")).toList()
        );
        assertEquals(List.of(1, 2), result.stream().map(RetrievalCandidate::rank).toList());
        assertEquals(
                List.of(ACTIVE_REVISION, ACTIVE_REVISION),
                result.stream().map(RetrievalCandidate::revisionId).toList()
        );
    }

    @Test
    void stopsAfterFourTimesTheRequestedWindow() {
        List<Integer> requestedLimits = new ArrayList<>();

        List<RetrievalCandidate> result = ActiveRevisionCandidates.load(
                TENANT_ID,
                2,
                limit -> {
                    requestedLimits.add(limit);
                    return java.util.stream.IntStream.range(0, limit)
                            .mapToObj(index -> candidate(index + 1, STALE_REVISION))
                            .toList();
                },
                retainingActiveRevision()
        );

        assertEquals(List.of(2, 4, 8), requestedLimits);
        assertEquals(List.of(), result);
    }

    @Test
    void stopsWhenTheBackendWindowIsExhausted() {
        List<Integer> requestedLimits = new ArrayList<>();

        List<RetrievalCandidate> result = ActiveRevisionCandidates.load(
                TENANT_ID,
                3,
                limit -> {
                    requestedLimits.add(limit);
                    return List.of(candidate(1, STALE_REVISION));
                },
                retainingActiveRevision()
        );

        assertEquals(List.of(3), requestedLimits);
        assertEquals(List.of(), result);
    }

    private static ActiveRevisionGuard retainingActiveRevision() {
        return new ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId tenantId,
                    DocumentId documentId,
                    UUID revisionId
            ) {
                return ACTIVE_REVISION.equals(revisionId);
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<RetrievalCandidate> candidates
            ) {
                return candidates.stream()
                        .filter(value -> ACTIVE_REVISION.equals(value.revisionId()))
                        .toList();
            }
        };
    }

    private static RetrievalCandidate candidate(int rank, UUID revisionId) {
        return new RetrievalCandidate(
                UUID.randomUUID(),
                TENANT_ID,
                new KnowledgeSpaceId("space-a"),
                new DocumentId(UUID.randomUUID()),
                revisionId,
                RetrievalChannel.KEYWORD,
                rank,
                0.9,
                "title",
                List.of("section"),
                "content",
                "file:///source.md",
                Map.of("originalRank", Integer.toString(rank))
        );
    }
}
