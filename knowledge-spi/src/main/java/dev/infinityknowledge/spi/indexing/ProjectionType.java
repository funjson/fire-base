package dev.infinityknowledge.spi.indexing;

/**
 * External projections produced from one immutable document revision.
 */
public enum ProjectionType {
    /** Dense embedding projection. */
    VECTOR,
    /** External keyword-search projection. */
    KEYWORD,
    /** Knowledge-graph projection. */
    GRAPH
}
