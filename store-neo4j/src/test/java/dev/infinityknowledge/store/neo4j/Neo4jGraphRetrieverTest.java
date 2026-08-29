package dev.infinityknowledge.store.neo4j;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.graph.KnowledgeGraph;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.graph.KnowledgeProvenance;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.graph.GraphEvidence;
import dev.infinityknowledge.spi.graph.GraphQuery;
import dev.infinityknowledge.spi.graph.GraphStore;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Neo4jGraphRetrieverTest {

    @Test
    void convertsAuthorizedGraphAssertionToRetrievalCandidate() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("architecture");
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        DocumentId documentId = DocumentId.random();
        KnowledgeProvenance provenance = new KnowledgeProvenance(
                UUID.randomUUID(),
                tenantId,
                spaceId,
                documentId,
                revisionId,
                chunkId,
                "obsidian://main/order.md",
                "Order Service depends on Redis.",
                0.9
        );
        CapturingStore store = new CapturingStore(List.of(new GraphEvidence(
                UUID.randomUUID(),
                new KnowledgeNodeId("service:order"),
                "Order Service",
                "SERVICE",
                new KnowledgeNodeId("database:redis"),
                "Redis",
                "DATABASE",
                "DEPENDS_ON",
                "Order Architecture",
                provenance,
                0.86,
                1
        )));
        Neo4jGraphRetriever retriever = new Neo4jGraphRetriever(
                store,
                retainingGuard(),
                new Neo4jGraphConfig("neo4j", 2, 50)
        );
        AccessScope scope = AccessScope.all(tenantId, Set.of(spaceId));
        KnowledgeQuery query = KnowledgeQuery.online(
                UUID.randomUUID(),
                new PrincipalContext(
                        tenantId,
                        new PrincipalId("reader"),
                        Set.of("reader"),
                        Set.of(),
                        false
                ),
                "What does Order Service depend on?",
                Set.of(spaceId),
                5,
                Map.of()
        );
        RetrievalRequest request = new RetrievalRequest(
                query,
                new QueryPlan(
                        query.text(),
                        query.text(),
                        Set.of(RetrievalChannel.GRAPH),
                        20
                ),
                scope
        );

        List<RetrievalCandidate> result = retriever.retrieve(request);

        assertEquals(RetrievalChannel.GRAPH, retriever.channel());
        assertEquals(1, result.size());
        assertEquals(chunkId, result.getFirst().chunkId());
        assertTrue(result.getFirst().content().contains("DEPENDS_ON"));
        assertEquals(2, store.queries.size());
        assertEquals(1, store.queries.getFirst().maxHops());
        assertEquals(scope, store.queries.getFirst().accessScope());
    }

    @Test
    void doesNotCallStoreWhenAccessIsDenied() {
        TenantId tenantId = new TenantId("tenant-a");
        CapturingStore store = new CapturingStore(List.of());
        Neo4jGraphRetriever retriever = new Neo4jGraphRetriever(
                store,
                retainingGuard(),
                new Neo4jGraphConfig("neo4j", 1, 10)
        );
        KnowledgeQuery query = KnowledgeQuery.online(
                UUID.randomUUID(),
                new PrincipalContext(
                        tenantId,
                        new PrincipalId("reader"),
                        Set.of(),
                        Set.of(),
                        false
                ),
                "Order Service",
                Set.of(),
                5,
                Map.of()
        );
        RetrievalRequest request = new RetrievalRequest(
                query,
                new QueryPlan(
                        query.text(),
                        query.text(),
                        Set.of(RetrievalChannel.GRAPH),
                        10
                ),
                AccessScope.denyAll(tenantId)
        );

        assertTrue(retriever.retrieve(request).isEmpty());
        assertTrue(store.queries.isEmpty());
    }

    @Test
    void inactiveRevisionCannotBecomeTheFrontierForAnotherHop() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("architecture");
        DocumentId activeDocument = DocumentId.random();
        UUID activeRevision = UUID.randomUUID();
        GraphEvidence firstHop = evidence(
                tenantId,
                spaceId,
                activeDocument,
                activeRevision,
                "order-service",
                "redis",
                "DEPENDS_ON"
        );
        GraphEvidence staleSecondHop = evidence(
                tenantId,
                spaceId,
                DocumentId.random(),
                UUID.randomUUID(),
                "redis",
                "inventory-service",
                "CALLS"
        );
        SequencedStore store = new SequencedStore(firstHop, staleSecondHop);
        Neo4jGraphRetriever retriever = new Neo4jGraphRetriever(
                store,
                activeOnly(activeDocument, activeRevision),
                new Neo4jGraphConfig("neo4j", 3, 50)
        );
        KnowledgeQuery query = KnowledgeQuery.online(
                UUID.randomUUID(),
                new PrincipalContext(
                        tenantId,
                        new PrincipalId("reader"),
                        Set.of("reader"),
                        Set.of(),
                        false
                ),
                "Order Service dependencies",
                Set.of(spaceId),
                10,
                Map.of()
        );
        RetrievalRequest request = new RetrievalRequest(
                query,
                new QueryPlan(
                        query.text(),
                        query.text(),
                        Set.of(RetrievalChannel.GRAPH),
                        20
                ),
                AccessScope.all(tenantId, Set.of(spaceId))
        );

        List<RetrievalCandidate> result = retriever.retrieve(request);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().content().contains("DEPENDS_ON"));
        assertEquals(2, store.queries.size());
        assertEquals(1, store.queries.getFirst().maxHops());
        assertEquals(1, store.queries.get(1).maxHops());
        assertEquals(
                Set.of(new KnowledgeNodeId("order-service"), new KnowledgeNodeId("redis")),
                store.queries.get(1).anchorIds()
        );
    }

    private static GraphEvidence evidence(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            String sourceId,
            String targetId,
            String relationType
    ) {
        return new GraphEvidence(
                UUID.randomUUID(),
                new KnowledgeNodeId(sourceId),
                sourceId,
                "SERVICE",
                new KnowledgeNodeId(targetId),
                targetId,
                "SERVICE",
                relationType,
                "Architecture",
                new KnowledgeProvenance(
                        UUID.randomUUID(),
                        tenantId,
                        spaceId,
                        documentId,
                        revisionId,
                        UUID.randomUUID(),
                        "obsidian://architecture",
                        sourceId + " " + relationType + " " + targetId,
                        0.95D
                ),
                0.9D,
                1
        );
    }

    private static ActiveRevisionGuard activeOnly(
            DocumentId activeDocument,
            UUID activeRevision
    ) {
        return new ActiveRevisionGuard() {
            @Override
            public boolean isActive(TenantId tenantId, DocumentId documentId, UUID revisionId) {
                return activeDocument.equals(documentId) && activeRevision.equals(revisionId);
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<RetrievalCandidate> candidates
            ) {
                return candidates.stream()
                        .filter(candidate -> activeDocument.equals(candidate.documentId()))
                        .filter(candidate -> activeRevision.equals(candidate.revisionId()))
                        .toList();
            }
        };
    }

    private static ActiveRevisionGuard retainingGuard() {
        return new ActiveRevisionGuard() {
            @Override
            public boolean isActive(TenantId tenantId, DocumentId documentId, UUID revisionId) {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<RetrievalCandidate> candidates
            ) {
                return candidates;
            }
        };
    }

    private static final class CapturingStore implements GraphStore {
        private final List<GraphEvidence> result;
        private final List<GraphQuery> queries = new java.util.ArrayList<>();

        private CapturingStore(List<GraphEvidence> result) {
            this.result = result;
        }

        @Override
        public void ensureSchema() {
        }

        @Override
        public void replaceRevision(
                dev.infinityknowledge.domain.document.KnowledgeDocument document,
                UUID revisionId,
                KnowledgeGraph graph
        ) {
        }

        @Override
        public List<GraphEvidence> search(GraphQuery query) {
            queries.add(query);
            return result;
        }
    }

    private static final class SequencedStore implements GraphStore {
        private final GraphEvidence firstHop;
        private final GraphEvidence secondHop;
        private final List<GraphQuery> queries = new java.util.ArrayList<>();

        private SequencedStore(GraphEvidence firstHop, GraphEvidence secondHop) {
            this.firstHop = firstHop;
            this.secondHop = secondHop;
        }

        @Override
        public void ensureSchema() {
        }

        @Override
        public void replaceRevision(
                dev.infinityknowledge.domain.document.KnowledgeDocument document,
                UUID revisionId,
                KnowledgeGraph graph
        ) {
        }

        @Override
        public List<GraphEvidence> search(GraphQuery query) {
            queries.add(query);
            return query.anchorIds().isEmpty() ? List.of(firstHop) : List.of(secondHop);
        }
    }
}
