package dev.infinityknowledge.domain;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.graph.KnowledgeEntity;
import dev.infinityknowledge.domain.graph.KnowledgeGraph;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.graph.KnowledgeProvenance;
import dev.infinityknowledge.domain.graph.KnowledgeRelation;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphDomainInvariantTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("architecture");
    private static final DocumentId DOCUMENT = DocumentId.random();
    private static final UUID REVISION = UUID.randomUUID();

    @Test
    void acceptsSourceBackedRelationship() {
        KnowledgeEntity order = entity("service:order", "Order Service");
        KnowledgeEntity redis = entity("database:redis", "Redis");
        KnowledgeRelation relation = new KnowledgeRelation(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                order.id(),
                redis.id(),
                "depends_on",
                0.93,
                Map.of("environment", "production"),
                List.of(provenance())
        );

        KnowledgeGraph graph = new KnowledgeGraph(
                TENANT,
                SPACE,
                List.of(order, redis),
                List.of(),
                List.of(relation)
        );

        assertEquals("DEPENDS_ON", graph.relations().getFirst().type());
        assertEquals(2L, graph.nodeIds().count());
    }

    @Test
    void rejectsRelationWhoseEndpointWasNotExtracted() {
        KnowledgeEntity order = entity("service:order", "Order Service");
        KnowledgeRelation relation = new KnowledgeRelation(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                order.id(),
                new KnowledgeNodeId("database:missing"),
                "DEPENDS_ON",
                0.9,
                Map.of(),
                List.of(provenance())
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeGraph(
                        TENANT,
                        SPACE,
                        List.of(order),
                        List.of(),
                        List.of(relation)
                )
        );
    }

    @Test
    void rejectsCrossTenantProvenance() {
        KnowledgeProvenance foreign = new KnowledgeProvenance(
                UUID.randomUUID(),
                new TenantId("tenant-b"),
                SPACE,
                DOCUMENT,
                REVISION,
                UUID.randomUUID(),
                "obsidian://vault/order.md",
                "Order Service depends on Redis.",
                0.9
        );
        KnowledgeEntity entity = new KnowledgeEntity(
                new KnowledgeNodeId("service:order"),
                TENANT,
                SPACE,
                "SERVICE",
                "Order Service",
                Set.of(),
                Map.of(),
                List.of(foreign)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeGraph(
                        TENANT,
                        SPACE,
                        List.of(entity),
                        List.of(),
                        List.of()
                )
        );
    }

    private static KnowledgeEntity entity(String id, String name) {
        return new KnowledgeEntity(
                new KnowledgeNodeId(id),
                TENANT,
                SPACE,
                "SERVICE",
                name,
                Set.of(name.toLowerCase()),
                Map.of(),
                List.of(provenance())
        );
    }

    private static KnowledgeProvenance provenance() {
        return new KnowledgeProvenance(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                DOCUMENT,
                REVISION,
                UUID.randomUUID(),
                "obsidian://vault/order.md",
                "Order Service depends on Redis.",
                0.93
        );
    }
}
