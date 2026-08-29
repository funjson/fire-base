package dev.infinityknowledge.store.milvus;

import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.vector.VectorIndex;
import dev.infinityknowledge.spi.vector.VectorIndexRecord;
import dev.infinityknowledge.spi.vector.VectorSearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that supported metadata filters reach the vector index unchanged.
 */
class MilvusVectorRetrieverTest {

    @Test
    void forwardsLanguageAndSourceTypeFiltersToMilvusSearch() {
        AtomicBoolean embeddingCalled = new AtomicBoolean();
        AtomicReference<VectorSearchRequest> captured = new AtomicReference<>();
        EmbeddingSpec spec = new EmbeddingSpec("zhipu", "embedding-3", 2);
        var retriever = new MilvusVectorRetriever(
                (texts, requestedSpec) -> {
                    embeddingCalled.set(true);
                    assertEquals(spec, requestedSpec);
                    return List.of(new EmbeddingVector(0, List.of(1.0D, 0.0D)));
                },
                spec,
                "v2",
                new RecordingVectorIndex(captured)
        );
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        var principal = new PrincipalContext(
                tenantId,
                new PrincipalId("reader-1"),
                Set.of("knowledge-reader"),
                Set.of(),
                false
        );
        Map<String, String> filters = Map.of(
                "language", "zh-CN",
                "sourceType", "OBSIDIAN"
        );
        var query = KnowledgeQuery.online(
                UUID.randomUUID(),
                principal,
                "Redis timeout",
                Set.of(spaceId),
                8,
                filters
        );
        var request = new RetrievalRequest(
                query,
                new QueryPlan(
                        query.text(),
                        query.text(),
                        Set.of(RetrievalChannel.VECTOR),
                        40
                ),
                AccessScope.all(tenantId, Set.of(spaceId))
        );

        assertTrue(retriever.retrieve(request).isEmpty());

        assertTrue(embeddingCalled.get());
        assertEquals(filters, captured.get().filters());
    }

    private record RecordingVectorIndex(
            AtomicReference<VectorSearchRequest> captured
    ) implements VectorIndex {
        @Override
        public void ensureGeneration(EmbeddingSpec spec, String generation) {
        }

        @Override
        public void upsert(List<VectorIndexRecord> records) {
            throw new AssertionError("projection must not be called");
        }

        @Override
        public List<RetrievalCandidate> search(VectorSearchRequest request) {
            captured.set(request);
            return List.of();
        }
    }
}
