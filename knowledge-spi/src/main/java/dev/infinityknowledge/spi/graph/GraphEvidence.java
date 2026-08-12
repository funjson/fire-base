package dev.infinityknowledge.spi.graph;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.graph.KnowledgeProvenance;

import java.util.Objects;
import java.util.UUID;

/**
 * One graph relationship assertion returned with its exact source.
 *
 * @param relationId logical relationship identifier
 * @param sourceId source node identifier
 * @param sourceName source node display name
 * @param sourceType source node type
 * @param targetId target node identifier
 * @param targetName target node display name
 * @param targetType target node type
 * @param relationType domain relationship type
 * @param documentTitle source document title
 * @param provenance exact source location
 * @param score normalized graph relevance
 * @param depth distance from the matched anchor
 */
public record GraphEvidence(
        UUID relationId,
        KnowledgeNodeId sourceId,
        String sourceName,
        String sourceType,
        KnowledgeNodeId targetId,
        String targetName,
        String targetType,
        String relationType,
        String documentTitle,
        KnowledgeProvenance provenance,
        double score,
        int depth
) {

    /**
     * Validates the evidence before it enters retrieval fusion.
     */
    public GraphEvidence {
        Objects.requireNonNull(relationId, "relationId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        sourceName = DomainChecks.requiredText(sourceName, "sourceName", 512);
        sourceType = DomainChecks.requiredText(sourceType, "sourceType", 64);
        Objects.requireNonNull(targetId, "targetId must not be null");
        targetName = DomainChecks.requiredText(targetName, "targetName", 512);
        targetType = DomainChecks.requiredText(targetType, "targetType", 64);
        relationType = DomainChecks.requiredText(relationType, "relationType", 64);
        documentTitle = DomainChecks.requiredText(documentTitle, "documentTitle", 512);
        Objects.requireNonNull(provenance, "provenance must not be null");
        score = DomainChecks.unitScore(score, "graph evidence score");
        if (depth < 1 || depth > 3) {
            throw new IllegalArgumentException("depth must be between 1 and 3");
        }
    }
}
