package dev.infinityknowledge.domain.graph;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Directed, source-backed relationship between two graph nodes.
 *
 * @param id stable logical relationship identifier
 * @param tenantId owning tenant
 * @param spaceId owning knowledge space
 * @param sourceId source graph node
 * @param targetId target graph node
 * @param type domain relationship type
 * @param confidence relationship confidence between zero and one
 * @param properties non-sensitive relationship attributes
 * @param provenance one or more exact source assertions
 */
public record KnowledgeRelation(
        UUID id,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        KnowledgeNodeId sourceId,
        KnowledgeNodeId targetId,
        String type,
        double confidence,
        Map<String, String> properties,
        List<KnowledgeProvenance> provenance
) {

    /**
     * Copies collections and disallows assertions without evidence.
     */
    public KnowledgeRelation {
        Objects.requireNonNull(id, "relation id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        type = GraphDomainChecks.type(type, "relation type");
        confidence = dev.infinityknowledge.domain.common.DomainChecks.unitScore(
                confidence,
                "relation confidence"
        );
        properties = GraphDomainChecks.properties(properties);
        provenance = List.copyOf(Objects.requireNonNull(
                provenance,
                "relation provenance must not be null"
        ));
        if (provenance.isEmpty()) {
            throw new IllegalArgumentException("relation provenance must not be empty");
        }
    }
}
