package dev.infinityknowledge.spi.indexing;

/**
 * Stable lifecycle states for one document projection.
 */
public enum ProjectionStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
