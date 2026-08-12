package dev.infinityknowledge.store.neo4j;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.graph.KnowledgeEntity;
import dev.infinityknowledge.domain.graph.KnowledgeGraph;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.graph.KnowledgeProvenance;
import dev.infinityknowledge.domain.graph.KnowledgeRelation;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.graph.GraphQuery;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Neo4jGraphStoreIT {

    @Test
    void projectsIdempotentlyAndEnforcesTenantAndDocumentScope() {
        Assumptions.assumeTrue(
                Boolean.parseBoolean(System.getenv("KNOWLEDGE_NEO4J_IT")),
                "set KNOWLEDGE_NEO4J_IT=true to run Neo4j integration tests"
        );
        String uri = environment("KNOWLEDGE_NEO4J_URI", "bolt://localhost:7687");
        String username = environment("KNOWLEDGE_NEO4J_USERNAME", "neo4j");
        String password = environment("KNOWLEDGE_NEO4J_PASSWORD", "infinity-knowledge");
        String database = environment("KNOWLEDGE_NEO4J_DATABASE", "neo4j");
        String suffix = UUID.randomUUID().toString();
        TenantId tenantId = new TenantId("it-" + suffix);
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("architecture");

        try (Driver driver = GraphDatabase.driver(
                URI.create(uri),
                AuthTokens.basic(username, password)
        )) {
            Neo4jGraphStore store = new Neo4jGraphStore(
                    driver,
                    new Neo4jGraphConfig(database, 2, 50),
                    JsonMapper.builder().build()
            );
            Fixture fixture = fixture(tenantId, spaceId);
            try {
                store.replaceRevision(fixture.document(), fixture.revisionId(), fixture.graph());
                store.replaceRevision(fixture.document(), fixture.revisionId(), fixture.graph());

                var result = store.search(new GraphQuery(
                        "Which database does Order Service depend on?",
                        Set.of(),
                        AccessScope.all(tenantId, Set.of(spaceId)),
                        1,
                        10
                ));
                assertEquals(1, result.size());
                assertEquals("DEPENDS_ON", result.getFirst().relationType());

                assertTrue(store.search(new GraphQuery(
                        "Order Service",
                        Set.of(),
                        AccessScope.all(new TenantId("foreign-tenant"), Set.of(spaceId)),
                        1,
                        10
                )).isEmpty());
                assertTrue(store.search(new GraphQuery(
                        "Order Service",
                        Set.of(),
                        AccessScope.only(
                                tenantId,
                                Set.of(spaceId),
                                Set.of(DocumentId.random().value().toString())
                        ),
                        1,
                        10
                )).isEmpty());
            } finally {
                cleanup(driver, database, tenantId);
            }
        }
    }

    private static Fixture fixture(TenantId tenantId, KnowledgeSpaceId spaceId) {
        DocumentId documentId = DocumentId.random();
        UUID revisionId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                tenantId,
                spaceId,
                "Order Architecture",
                new SourceDescriptor(
                        "it",
                        SourceType.API,
                        "order-architecture",
                        "https://example.invalid/order-architecture",
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                90,
                Map.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
        KnowledgeProvenance provenance = new KnowledgeProvenance(
                UUID.randomUUID(),
                tenantId,
                spaceId,
                documentId,
                revisionId,
                chunkId,
                document.source().uri(),
                "Order Service depends on Redis.",
                0.96
        );
        KnowledgeEntity order = new KnowledgeEntity(
                new KnowledgeNodeId("service:order"),
                tenantId,
                spaceId,
                "SERVICE",
                "Order Service",
                Set.of("Order"),
                Map.of(),
                List.of(provenance)
        );
        KnowledgeEntity redis = new KnowledgeEntity(
                new KnowledgeNodeId("database:redis"),
                tenantId,
                spaceId,
                "DATABASE",
                "Redis",
                Set.of(),
                Map.of(),
                List.of(provenance)
        );
        KnowledgeRelation relation = new KnowledgeRelation(
                UUID.randomUUID(),
                tenantId,
                spaceId,
                order.id(),
                redis.id(),
                "DEPENDS_ON",
                0.94,
                Map.of(),
                List.of(provenance)
        );
        return new Fixture(document, revisionId, new KnowledgeGraph(
                tenantId,
                spaceId,
                List.of(order, redis),
                List.of(),
                List.of(relation)
        ));
    }

    private static void cleanup(Driver driver, String database, TenantId tenantId) {
        try (var session = driver.session(
                SessionConfig.builder().withDatabase(database).build()
        )) {
            session.run(
                    "MATCH (node) WHERE node.tenant_id = $tenantId DETACH DELETE node",
                    Map.of("tenantId", tenantId.value())
            ).consume();
        }
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private record Fixture(
            KnowledgeDocument document,
            UUID revisionId,
            KnowledgeGraph graph
    ) {
    }
}
