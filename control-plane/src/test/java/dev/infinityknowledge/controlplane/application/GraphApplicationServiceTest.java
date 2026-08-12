package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.GraphApi;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.graph.KnowledgeGraph;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.graph.KnowledgeProvenance;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.graph.GraphEvidence;
import dev.infinityknowledge.spi.graph.GraphQuery;
import dev.infinityknowledge.spi.graph.GraphStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphApplicationServiceTest {
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");

    @Test
    void mapsOnlyAclScopedSourceBackedEdges() {
        AtomicReference<GraphQuery> captured = new AtomicReference<>();
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        GraphStore store = new StubGraphStore(query -> {
            captured.set(query);
            return List.of(new GraphEvidence(
                    UUID.randomUUID(),
                    new KnowledgeNodeId("order-service"),
                    "订单服务",
                    "SERVICE",
                    new KnowledgeNodeId("redis"),
                    "Redis",
                    "DATABASE",
                    "DEPENDS_ON",
                    "订单服务架构",
                    new KnowledgeProvenance(
                            UUID.randomUUID(),
                            TENANT,
                            SPACE,
                            documentId,
                            revisionId,
                            chunkId,
                            "obsidian://engineering/order-service",
                            "订单服务依赖 Redis",
                            0.96D
                    ),
                    0.91D,
                    1
            ));
        });
        GraphApplicationService service = new GraphApplicationService(
                (principal, requested) -> AccessScope.only(
                        TENANT,
                        Set.of(SPACE),
                        Set.of(documentId.value().toString())
                ),
                Optional.of(store),
                activeGuard(documentId, revisionId)
        );

        GraphApi.SearchResponse response = service.search(
                admin(),
                new GraphApi.SearchRequest("  订单服务   依赖  ", Set.of(SPACE.value()), 1, 20)
        );

        assertEquals("订单服务 依赖", captured.get().normalizedQuery());
        assertEquals(AccessScope.Mode.ONLY, captured.get().accessScope().mode());
        assertEquals(1, response.edges().size());
        assertEquals(chunkId, response.edges().getFirst().provenance().chunkId());
        assertEquals("DEPENDS_ON", response.edges().getFirst().type());
    }

    @Test
    void rejectsNonAdminBeforeQueryingGraph() {
        GraphApplicationService service = new GraphApplicationService(
                (principal, requested) -> AccessScope.all(TENANT, Set.of(SPACE)),
                Optional.of(new StubGraphStore(query -> {
                    throw new AssertionError("graph must not be queried");
                })),
                activeGuard(DocumentId.random(), UUID.randomUUID())
        );
        PrincipalContext reader = new PrincipalContext(
                TENANT,
                new PrincipalId("reader"),
                Set.of("knowledge-reader"),
                Set.of(),
                false
        );

        assertThrows(
                AccessDeniedException.class,
                () -> service.search(reader, new GraphApi.SearchRequest("order", Set.of(), 1, 10))
        );
    }

    @Test
    void reportsDisabledGraphCapabilityWithoutDroppingTheApiRoute() {
        GraphApplicationService service = new GraphApplicationService(
                (principal, requested) -> AccessScope.all(TENANT, Set.of(SPACE)),
                Optional.empty(),
                activeGuard(DocumentId.random(), UUID.randomUUID())
        );

        assertThrows(
                GraphCapabilityUnavailableException.class,
                () -> service.search(
                        admin(),
                        new GraphApi.SearchRequest("order", Set.of(SPACE.value()), 1, 10)
                )
        );
    }

    @Test
    void neverUsesAnInactiveRevisionAsAMultiHopBridge() {
        DocumentId activeDocument = DocumentId.random();
        UUID activeRevision = UUID.randomUUID();
        DocumentId archivedDocument = DocumentId.random();
        UUID archivedRevision = UUID.randomUUID();
        List<GraphQuery> queries = new ArrayList<>();
        GraphEvidence firstHop = evidence(
                activeDocument,
                activeRevision,
                "order-service",
                "redis",
                "DEPENDS_ON"
        );
        GraphEvidence staleSecondHop = evidence(
                archivedDocument,
                archivedRevision,
                "redis",
                "inventory-service",
                "CALLS"
        );
        GraphStore store = new StubGraphStore(query -> {
            queries.add(query);
            return query.anchorIds().isEmpty() ? List.of(firstHop) : List.of(staleSecondHop);
        });
        GraphApplicationService service = new GraphApplicationService(
                (principal, requested) -> AccessScope.all(TENANT, Set.of(SPACE)),
                Optional.of(store),
                activeGuard(activeDocument, activeRevision)
        );

        GraphApi.SearchResponse response = service.search(
                admin(),
                new GraphApi.SearchRequest("order service", Set.of(SPACE.value()), 3, 20)
        );

        assertEquals(1, response.edges().size());
        assertEquals("DEPENDS_ON", response.edges().getFirst().type());
        assertEquals(2, queries.size());
        assertEquals(1, queries.getFirst().maxHops());
        assertEquals(1, queries.get(1).maxHops());
        assertEquals(
                Set.of(new KnowledgeNodeId("order-service"), new KnowledgeNodeId("redis")),
                queries.get(1).anchorIds()
        );
    }

    private static GraphEvidence evidence(
            DocumentId documentId,
            UUID revisionId,
            String sourceId,
            String targetId,
            String type
    ) {
        return new GraphEvidence(
                UUID.randomUUID(),
                new KnowledgeNodeId(sourceId),
                sourceId,
                "SERVICE",
                new KnowledgeNodeId(targetId),
                targetId,
                "SERVICE",
                type,
                "Architecture",
                new KnowledgeProvenance(
                        UUID.randomUUID(),
                        TENANT,
                        SPACE,
                        documentId,
                        revisionId,
                        UUID.randomUUID(),
                        "obsidian://engineering/architecture",
                        sourceId + " " + type + " " + targetId,
                        0.95D
                ),
                0.9D,
                1
        );
    }

    private static PrincipalContext admin() {
        return new PrincipalContext(
                TENANT,
                new PrincipalId("admin"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }

    private static dev.infinityknowledge.spi.indexing.ActiveRevisionGuard activeGuard(
            DocumentId documentId,
            UUID revisionId
    ) {
        return new dev.infinityknowledge.spi.indexing.ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId tenantId,
                    DocumentId candidateDocumentId,
                    UUID candidateRevisionId
            ) {
                return TENANT.equals(tenantId)
                        && documentId.equals(candidateDocumentId)
                        && revisionId.equals(candidateRevisionId);
            }

            @Override
            public List<dev.infinityknowledge.domain.retrieval.RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<dev.infinityknowledge.domain.retrieval.RetrievalCandidate> candidates
            ) {
                return candidates.stream()
                        .filter(candidate -> TENANT.equals(tenantId))
                        .filter(candidate -> documentId.equals(candidate.documentId()))
                        .filter(candidate -> revisionId.equals(candidate.revisionId()))
                        .toList();
            }
        };
    }

    private record StubGraphStore(
            java.util.function.Function<GraphQuery, List<GraphEvidence>> search
    ) implements GraphStore {
        @Override
        public void ensureSchema() {
        }

        @Override
        public void replaceRevision(
                dev.infinityknowledge.domain.document.KnowledgeDocument document,
                UUID revisionId,
                KnowledgeGraph graph
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GraphEvidence> search(GraphQuery query) {
            return search.apply(query);
        }
    }
}
