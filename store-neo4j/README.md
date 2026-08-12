# store-neo4j

Neo4j adapter for the source-backed knowledge graph.

The module owns only graph persistence and retrieval. Entity and relation extraction remains
behind `GraphExtractor`, while authorization remains an explicit `AccessScope` on every query.
Relationships are stored as fixed `KNOWLEDGE_RELATION` edges with a domain `type` property; no
LLM-generated label or relationship name is interpolated into Cypher.
