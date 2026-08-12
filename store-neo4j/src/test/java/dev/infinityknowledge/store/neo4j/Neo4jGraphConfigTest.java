package dev.infinityknowledge.store.neo4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Neo4jGraphConfigTest {

    @Test
    void acceptsBoundedConfiguration() {
        Neo4jGraphConfig config = new Neo4jGraphConfig("neo4j", 2, 100);

        assertEquals(2, config.maxHops());
        assertEquals(100, config.maxResults());
    }

    @Test
    void rejectsUnboundedTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Neo4jGraphConfig("neo4j", 4, 100)
        );
    }
}
