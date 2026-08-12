package dev.infinityknowledge.spi.graph;

import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.graph.KnowledgeGraph;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for the source-backed knowledge graph.
 */
public interface GraphStore {

    /**
     * Creates idempotent constraints and indexes required by the adapter.
     */
    void ensureSchema();

    /**
     * Replaces only assertions produced from the specified immutable revision.
     */
    void replaceRevision(
            KnowledgeDocument document,
            UUID revisionId,
            KnowledgeGraph graph
    );

    /**
     * Returns authorized relationship evidence ordered by graph relevance.
     * Callers that validate lifecycle state outside the graph store must traverse one hop
     * at a time and validate every returned assertion before using its nodes as anchors.
     */
    List<GraphEvidence> search(GraphQuery query);
}
