package dev.infinityknowledge.spi.graph;

import dev.infinityknowledge.domain.graph.KnowledgeGraph;
import dev.infinityknowledge.spi.indexing.ProjectionSource;

/**
 * Extracts source-backed entities, events and relationships from one document revision.
 */
public interface GraphExtractor {

    /**
     * Returns a graph whose every assertion can be traced to the supplied revision.
     */
    KnowledgeGraph extract(ProjectionSource source);
}
