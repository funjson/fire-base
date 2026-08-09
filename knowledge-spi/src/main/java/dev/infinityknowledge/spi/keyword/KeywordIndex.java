package dev.infinityknowledge.spi.keyword;

import dev.infinityknowledge.spi.indexing.ProjectionSource;

/**
 * External keyword index write port.
 */
public interface KeywordIndex {

    /**
     * Idempotently publishes all searchable chunks for one immutable document revision.
     */
    void upsert(ProjectionSource source);
}
