package dev.infinityknowledge.domain.graph;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Immutable graph extraction for one tenant and knowledge space.
 *
 * @param tenantId owning tenant
 * @param spaceId owning knowledge space
 * @param entities extracted entities
 * @param events extracted events
 * @param relations source-backed relationships
 */
public record KnowledgeGraph(
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        List<KnowledgeEntity> entities,
        List<KnowledgeEvent> events,
        List<KnowledgeRelation> relations
) {

    /**
     * Verifies scope consistency, unique identifiers and relationship endpoints.
     */
    public KnowledgeGraph {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        entities = List.copyOf(Objects.requireNonNull(entities, "entities must not be null"));
        events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations must not be null"));

        Set<KnowledgeNodeId> nodeIds = new HashSet<>();
        for (KnowledgeEntity entity : entities) {
            requireScope(tenantId, spaceId, entity.tenantId(), entity.spaceId(), "entity");
            if (!nodeIds.add(entity.id())) {
                throw new IllegalArgumentException("duplicate graph node id: " + entity.id());
            }
            requireProvenanceScope(tenantId, spaceId, entity.provenance());
        }
        for (KnowledgeEvent event : events) {
            requireScope(tenantId, spaceId, event.tenantId(), event.spaceId(), "event");
            if (!nodeIds.add(event.id())) {
                throw new IllegalArgumentException("duplicate graph node id: " + event.id());
            }
            requireProvenanceScope(tenantId, spaceId, event.provenance());
        }
        Set<UUID> relationIds = new HashSet<>();
        for (KnowledgeRelation relation : relations) {
            requireScope(tenantId, spaceId, relation.tenantId(), relation.spaceId(), "relation");
            if (!relationIds.add(relation.id())) {
                throw new IllegalArgumentException("duplicate relation id: " + relation.id());
            }
            if (!nodeIds.contains(relation.sourceId()) || !nodeIds.contains(relation.targetId())) {
                throw new IllegalArgumentException(
                        "relation endpoints must be present in the same extraction"
                );
            }
            requireProvenanceScope(tenantId, spaceId, relation.provenance());
        }
    }

    /**
     * Returns whether the extraction contains no graph facts.
     */
    public boolean isEmpty() {
        return entities.isEmpty() && events.isEmpty() && relations.isEmpty();
    }

    /**
     * Streams every graph node identifier without exposing a common mutable base type.
     */
    public Stream<KnowledgeNodeId> nodeIds() {
        return Stream.concat(
                entities.stream().map(KnowledgeEntity::id),
                events.stream().map(KnowledgeEvent::id)
        );
    }

    private static void requireScope(
            TenantId expectedTenant,
            KnowledgeSpaceId expectedSpace,
            TenantId actualTenant,
            KnowledgeSpaceId actualSpace,
            String subject
    ) {
        if (!expectedTenant.equals(actualTenant) || !expectedSpace.equals(actualSpace)) {
            throw new IllegalArgumentException(subject + " scope differs from graph scope");
        }
    }

    private static void requireProvenanceScope(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            List<KnowledgeProvenance> provenance
    ) {
        boolean invalid = provenance.stream().anyMatch(source ->
                !tenantId.equals(source.tenantId()) || !spaceId.equals(source.spaceId())
        );
        if (invalid) {
            throw new IllegalArgumentException("provenance scope differs from graph scope");
        }
    }
}
