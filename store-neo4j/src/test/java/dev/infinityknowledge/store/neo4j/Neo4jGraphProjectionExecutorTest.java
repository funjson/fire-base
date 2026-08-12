package dev.infinityknowledge.store.neo4j;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.graph.KnowledgeEntity;
import dev.infinityknowledge.domain.graph.KnowledgeGraph;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.graph.KnowledgeProvenance;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.graph.GraphEvidence;
import dev.infinityknowledge.spi.graph.GraphQuery;
import dev.infinityknowledge.spi.graph.GraphStore;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Neo4jGraphProjectionExecutorTest {

    @Test
    void publishesActiveSourceBackedExtraction() {
        Fixture fixture = fixture();
        CapturingGraphStore store = new CapturingGraphStore();
        AtomicBoolean extracted = new AtomicBoolean();
        Neo4jGraphProjectionExecutor executor = new Neo4jGraphProjectionExecutor(
                source -> {
                    extracted.set(true);
                    return graph(fixture, fixture.document().id());
                },
                store,
                guard(true)
        );

        executor.project(fixture.source());

        assertEquals(ProjectionType.GRAPH, executor.projectionType());
        assertTrue(extracted.get());
        assertEquals(fixture.revisionId(), store.revisionId);
        assertEquals(1, store.graph.entities().size());
    }

    @Test
    void doesNotExtractInactiveRevision() {
        Fixture fixture = fixture();
        AtomicBoolean extracted = new AtomicBoolean();
        CapturingGraphStore store = new CapturingGraphStore();
        Neo4jGraphProjectionExecutor executor = new Neo4jGraphProjectionExecutor(
                source -> {
                    extracted.set(true);
                    return graph(fixture, fixture.document().id());
                },
                store,
                guard(false)
        );

        executor.project(fixture.source());

        assertFalse(extracted.get());
        assertEquals(null, store.graph);
    }

    @Test
    void rejectsProvenanceOutsideProjectedDocument() {
        Fixture fixture = fixture();
        Neo4jGraphProjectionExecutor executor = new Neo4jGraphProjectionExecutor(
                source -> graph(fixture, DocumentId.random()),
                new CapturingGraphStore(),
                guard(true)
        );

        assertThrows(IllegalArgumentException.class, () -> executor.project(fixture.source()));
    }

    private static Fixture fixture() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("architecture");
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeDocument document = new KnowledgeDocument(
                DocumentId.random(),
                tenantId,
                spaceId,
                "Order Architecture",
                new SourceDescriptor(
                        "obsidian-main",
                        SourceType.OBSIDIAN,
                        "order.md",
                        "obsidian://main/order.md",
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                80,
                Map.of("language", "en"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        KnowledgeChunk chunk = new KnowledgeChunk(
                chunkId,
                tenantId,
                spaceId,
                document.id(),
                revisionId,
                List.of(UUID.randomUUID()),
                0,
                List.of("Architecture"),
                "Order Service depends on Redis.",
                "abc123",
                Map.of()
        );
        return new Fixture(document, revisionId, chunk, new ProjectionSource(
                document,
                List.of(chunk)
        ));
    }

    private static KnowledgeGraph graph(Fixture fixture, DocumentId sourceDocument) {
        KnowledgeProvenance provenance = new KnowledgeProvenance(
                UUID.randomUUID(),
                fixture.document().tenantId(),
                fixture.document().spaceId(),
                sourceDocument,
                fixture.revisionId(),
                fixture.chunk().id(),
                fixture.document().source().uri(),
                fixture.chunk().content(),
                0.95
        );
        KnowledgeEntity entity = new KnowledgeEntity(
                new KnowledgeNodeId("service:order"),
                fixture.document().tenantId(),
                fixture.document().spaceId(),
                "SERVICE",
                "Order Service",
                Set.of("order"),
                Map.of(),
                List.of(provenance)
        );
        return new KnowledgeGraph(
                fixture.document().tenantId(),
                fixture.document().spaceId(),
                List.of(entity),
                List.of(),
                List.of()
        );
    }

    private static ActiveRevisionGuard guard(boolean active) {
        return new ActiveRevisionGuard() {
            @Override
            public boolean isActive(TenantId tenantId, DocumentId documentId, UUID revisionId) {
                return active;
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<RetrievalCandidate> candidates
            ) {
                return active ? candidates : List.of();
            }
        };
    }

    private record Fixture(
            KnowledgeDocument document,
            UUID revisionId,
            KnowledgeChunk chunk,
            ProjectionSource source
    ) {
    }

    private static final class CapturingGraphStore implements GraphStore {
        private UUID revisionId;
        private KnowledgeGraph graph;

        @Override
        public void ensureSchema() {
        }

        @Override
        public void replaceRevision(
                KnowledgeDocument document,
                UUID revisionId,
                KnowledgeGraph graph
        ) {
            this.revisionId = revisionId;
            this.graph = graph;
        }

        @Override
        public List<GraphEvidence> search(GraphQuery query) {
            return List.of();
        }
    }
}
