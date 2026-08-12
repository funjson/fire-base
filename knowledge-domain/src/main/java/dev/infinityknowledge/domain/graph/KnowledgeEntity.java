package dev.infinityknowledge.domain.graph;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical enterprise entity extracted from governed source material.
 *
 * @param id stable node identifier
 * @param tenantId owning tenant
 * @param spaceId owning knowledge space
 * @param type domain entity type such as SERVICE or PERSON
 * @param name canonical display name
 * @param aliases alternative names used for graph anchoring
 * @param properties non-sensitive domain attributes
 * @param provenance source locations mentioning the entity
 */
public record KnowledgeEntity(
        KnowledgeNodeId id,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        String type,
        String name,
        Set<String> aliases,
        Map<String, String> properties,
        List<KnowledgeProvenance> provenance
) {

    /**
     * Copies collections and requires at least one governed source.
     */
    public KnowledgeEntity {
        Objects.requireNonNull(id, "entity id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        type = GraphDomainChecks.type(type, "entity type");
        name = DomainChecks.requiredText(name, "entity name", 512);
        aliases = GraphDomainChecks.aliases(aliases);
        properties = GraphDomainChecks.properties(properties);
        provenance = List.copyOf(Objects.requireNonNull(
                provenance,
                "entity provenance must not be null"
        ));
        if (provenance.isEmpty()) {
            throw new IllegalArgumentException("entity provenance must not be empty");
        }
    }
}
