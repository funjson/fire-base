package dev.infinityknowledge.spi.graph;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.spi.access.AccessScope;

import java.util.Objects;
import java.util.Set;

/**
 * Bounded, ACL-aware graph traversal request.
 *
 * @param normalizedQuery text used to locate graph anchors when explicit anchors are absent
 * @param anchorIds optional exact anchor identifiers
 * @param accessScope mandatory tenant, space and document authorization
 * @param maxHops maximum relationship depth, limited to three
 * @param limit maximum returned relationship assertions
 */
public record GraphQuery(
        String normalizedQuery,
        Set<KnowledgeNodeId> anchorIds,
        AccessScope accessScope,
        int maxHops,
        int limit
) {

    /**
     * Copies scope inputs and bounds graph work.
     */
    public GraphQuery {
        normalizedQuery = DomainChecks.requiredText(normalizedQuery, "normalizedQuery", 16_000);
        anchorIds = Set.copyOf(Objects.requireNonNull(anchorIds, "anchorIds must not be null"));
        Objects.requireNonNull(accessScope, "accessScope must not be null");
        if (maxHops < 1 || maxHops > 3) {
            throw new IllegalArgumentException("maxHops must be between 1 and 3");
        }
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
    }
}
