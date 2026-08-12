package dev.infinityknowledge.domain.graph;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Time-bound enterprise event represented as a graph node.
 *
 * @param id stable node identifier
 * @param tenantId owning tenant
 * @param spaceId owning knowledge space
 * @param type domain event type such as RELEASE or INCIDENT
 * @param name event display name
 * @param occurredAt event occurrence time
 * @param properties non-sensitive event attributes
 * @param provenance source locations supporting the event
 */
public record KnowledgeEvent(
        KnowledgeNodeId id,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        String type,
        String name,
        Instant occurredAt,
        Map<String, String> properties,
        List<KnowledgeProvenance> provenance
) {

    /**
     * Copies collections and requires a precise timestamp and source.
     */
    public KnowledgeEvent {
        Objects.requireNonNull(id, "event id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        type = GraphDomainChecks.type(type, "event type");
        name = DomainChecks.requiredText(name, "event name", 512);
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        properties = GraphDomainChecks.properties(properties);
        provenance = List.copyOf(Objects.requireNonNull(
                provenance,
                "event provenance must not be null"
        ));
        if (provenance.isEmpty()) {
            throw new IllegalArgumentException("event provenance must not be empty");
        }
    }
}
