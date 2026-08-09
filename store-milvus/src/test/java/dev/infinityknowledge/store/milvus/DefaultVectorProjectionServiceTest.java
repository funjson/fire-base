package dev.infinityknowledge.store.milvus;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.embedding.EmbeddingVector;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.vector.VectorIndex;
import dev.infinityknowledge.spi.vector.VectorIndexRecord;
import dev.infinityknowledge.spi.vector.VectorSearchRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultVectorProjectionServiceTest {

    @Test
    void doesNotPublishWhenRevisionChangesWhileEmbedding() {
        EmbeddingSpec spec = new EmbeddingSpec("test", "model", 2);
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        KnowledgeChunk chunk = new KnowledgeChunk(
                UUID.randomUUID(),
                tenantId,
                spaceId,
                documentId,
                revisionId,
                List.of(UUID.randomUUID()),
                0,
                List.of("Test"),
                "content",
                "hash",
                Map.of()
        );
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                tenantId,
                spaceId,
                "Document",
                new SourceDescriptor("test", SourceType.API, "document", "urn:test", Map.of()),
                DocumentStatus.ACTIVE,
                100,
                Map.of(),
                Instant.parse("2026-08-03T00:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z")
        );
        AtomicInteger checks = new AtomicInteger();
        ActiveRevisionGuard changingRevision = new ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId ignoredTenant,
                    DocumentId ignoredDocument,
                    UUID ignoredRevision
            ) {
                return checks.getAndIncrement() == 0;
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId ignoredTenant,
                    List<RetrievalCandidate> candidates
            ) {
                return candidates;
            }
        };
        RecordingVectorIndex vectorIndex = new RecordingVectorIndex();
        DefaultVectorProjectionService service = new DefaultVectorProjectionService(
                (texts, ignoredSpec) -> List.of(new EmbeddingVector(0, List.of(1.0D, 0.0D))),
                spec,
                "generation-a",
                vectorIndex,
                changingRevision
        );

        service.project(document, List.of(chunk));

        assertEquals(2, checks.get());
        assertFalse(vectorIndex.upserted);
    }

    private static final class RecordingVectorIndex implements VectorIndex {
        private boolean upserted;

        @Override
        public void ensureGeneration(EmbeddingSpec spec, String generation) {
        }

        @Override
        public void upsert(List<VectorIndexRecord> records) {
            upserted = true;
        }

        @Override
        public List<RetrievalCandidate> search(VectorSearchRequest request) {
            return List.of();
        }
    }
}
