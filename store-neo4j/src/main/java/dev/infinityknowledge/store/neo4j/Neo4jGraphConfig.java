package dev.infinityknowledge.store.neo4j;

import dev.infinityknowledge.domain.common.DomainChecks;

/**
 * Bounded graph traversal settings for one Neo4j database.
 *
 * @param database Neo4j database name
 * @param maxHops maximum relationship depth used by retrieval
 * @param maxResults hard cap applied independently of a query plan
 */
public record Neo4jGraphConfig(
        String database,
        int maxHops,
        int maxResults
) {

    /**
     * Validates resource limits before any Cypher is produced.
     */
    public Neo4jGraphConfig {
        database = DomainChecks.requiredText(database, "database", 128);
        if (!database.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("database contains unsupported characters");
        }
        if (maxHops < 1 || maxHops > 3) {
            throw new IllegalArgumentException("maxHops must be between 1 and 3");
        }
        if (maxResults < 1 || maxResults > 1_000) {
            throw new IllegalArgumentException("maxResults must be between 1 and 1000");
        }
    }
}
