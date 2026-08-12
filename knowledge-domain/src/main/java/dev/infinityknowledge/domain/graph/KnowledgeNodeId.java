package dev.infinityknowledge.domain.graph;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * Stable identifier shared by graph entities and events inside one knowledge space.
 *
 * @param value extractor-defined stable value
 */
public record KnowledgeNodeId(String value) {

    /**
     * Normalizes the identifier before it is used in graph keys.
     */
    public KnowledgeNodeId {
        value = DomainChecks.requiredText(value, "knowledgeNodeId", 256);
    }
}
