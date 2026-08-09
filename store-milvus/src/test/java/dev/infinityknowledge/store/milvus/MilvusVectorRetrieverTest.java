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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that vector retrieval never silently ignores metadata filters.
 */
class MilvusVectorRetrieverTest {

    @Test
    void rejectsMetadataFiltersBeforeCallingEmbeddingOrMilvus() {
        AtomicBoolean embeddingCalled = new AtomicBoolean();
        EmbeddingSpec spec = new EmbeddingSpec("zhipu", "embedding-3", 2);
        var retriever = new MilvusVectorRetriever(
                (texts, requestedSpec) -> {
                    embeddingCalled.set(true);
                    throw new AssertionError("embedding must not be called");
                },
                spec,
                "v1",
                new UnusedVectorIndex()
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
        var query = new KnowledgeQuery(
                UUID.randomUUID(),
                principal,
                "Redis timeout",
                Set.of(spaceId),
                8,
                Map.of("language", "zh-CN")
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

        assertThrows(IllegalArgumentException.class, () -> retriever.retrieve(request));
        assertFalse(embeddingCalled.get());
    }

    private static final class UnusedVectorIndex implements VectorIndex {
        @Override
        public void ensureGeneration(EmbeddingSpec spec, String generation) {
            throw new AssertionError("Milvus must not be called");
        }

        @Override
        public void upsert(List<VectorIndexRecord> records) {
            throw new AssertionError("Milvus must not be called");
        }

        @Override
        public List<RetrievalCandidate> search(VectorSearchRequest request) {
            throw new AssertionError("Milvus must not be called");
        }
    }
}
