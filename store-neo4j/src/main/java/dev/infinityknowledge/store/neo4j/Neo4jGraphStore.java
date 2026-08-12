package dev.infinityknowledge.store.neo4j;

import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.graph.KnowledgeEntity;
import dev.infinityknowledge.domain.graph.KnowledgeEvent;
import dev.infinityknowledge.domain.graph.KnowledgeGraph;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.graph.KnowledgeProvenance;
import dev.infinityknowledge.domain.graph.KnowledgeRelation;
import dev.infinityknowledge.spi.graph.GraphEvidence;
import dev.infinityknowledge.spi.graph.GraphQuery;
import dev.infinityknowledge.spi.graph.GraphStore;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Neo4j graph projection and bounded traversal adapter.
 *
 * <p>All labels and relationship names are constants. Extracted graph types remain data
 * properties, so untrusted model output is never interpolated into Cypher.</p>
 */
public final class Neo4jGraphStore implements GraphStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE CONSTRAINT infinity_knowledge_graph_node_key IF NOT EXISTS
            FOR (node:InfinityKnowledgeGraphNode) REQUIRE node.key IS UNIQUE
            """,
            """
            CREATE CONSTRAINT infinity_knowledge_graph_revision_key IF NOT EXISTS
            FOR (revision:InfinityKnowledgeGraphRevision) REQUIRE revision.key IS UNIQUE
            """,
            """
            CREATE INDEX infinity_knowledge_graph_node_scope IF NOT EXISTS
            FOR (node:InfinityKnowledgeGraphNode) ON (node.tenant_id, node.space_id)
            """,
            """
            CREATE INDEX infinity_knowledge_graph_relation_scope IF NOT EXISTS
            FOR ()-[relation:INFINITY_KNOWLEDGE_RELATION]-()
            ON (relation.tenant_id, relation.space_id)
            """
    );

    private static final String UPSERT_REVISION = """
            MERGE (revision:InfinityKnowledgeGraphRevision {key: $revisionKey})
            SET revision.tenant_id = $tenantId,
                revision.space_id = $spaceId,
                revision.document_id = $documentId,
                revision.revision_id = $revisionId,
                revision.document_title = $documentTitle,
                revision.source_uri = $sourceUri,
                revision.authority = $authority,
                revision.updated_at = $updatedAt
            """;

    private static final String DELETE_REVISION_ASSERTIONS = """
            MATCH ()-[relation:INFINITY_KNOWLEDGE_RELATION]-()
            WHERE relation.tenant_id = $tenantId
              AND relation.space_id = $spaceId
              AND relation.document_id = $documentId
              AND relation.revision_id = $revisionId
            DELETE relation
            """;

    private static final String DELETE_REVISION_MENTIONS = """
            MATCH ()-[mention:INFINITY_MENTIONED_IN]->()
            WHERE mention.tenant_id = $tenantId
              AND mention.space_id = $spaceId
              AND mention.document_id = $documentId
              AND mention.revision_id = $revisionId
            DELETE mention
            """;

    private static final String UPSERT_ENTITIES = """
            UNWIND $nodes AS row
            MERGE (node:InfinityKnowledgeGraphNode {key: row.key})
            WITH node, row,
                 node.source_rank IS NULL OR row.source_rank >= node.source_rank AS replaceCanonical
            SET node:InfinityKnowledgeEntity,
                node.tenant_id = row.tenant_id,
                node.space_id = row.space_id,
                node.node_id = row.node_id,
                node.type = CASE WHEN replaceCanonical THEN row.type ELSE node.type END,
                node.name = CASE WHEN replaceCanonical THEN row.name ELSE node.name END,
                node.aliases = reduce(known = coalesce(node.aliases, []), alias IN row.aliases |
                  CASE WHEN alias IN known THEN known ELSE known + alias END
                ),
                node.properties_json = CASE WHEN replaceCanonical
                  THEN row.properties_json ELSE node.properties_json END,
                node.source_rank = CASE WHEN replaceCanonical
                  THEN row.source_rank ELSE node.source_rank END,
                node.updated_at = row.updated_at
            """;

    private static final String UPSERT_EVENTS = """
            UNWIND $nodes AS row
            MERGE (node:InfinityKnowledgeGraphNode {key: row.key})
            WITH node, row,
                 node.source_rank IS NULL OR row.source_rank >= node.source_rank AS replaceCanonical
            SET node:InfinityKnowledgeEvent,
                node.tenant_id = row.tenant_id,
                node.space_id = row.space_id,
                node.node_id = row.node_id,
                node.type = CASE WHEN replaceCanonical THEN row.type ELSE node.type END,
                node.name = CASE WHEN replaceCanonical THEN row.name ELSE node.name END,
                node.aliases = [],
                node.occurred_at = CASE WHEN replaceCanonical
                  THEN row.occurred_at ELSE node.occurred_at END,
                node.properties_json = CASE WHEN replaceCanonical
                  THEN row.properties_json ELSE node.properties_json END,
                node.source_rank = CASE WHEN replaceCanonical
                  THEN row.source_rank ELSE node.source_rank END,
                node.updated_at = row.updated_at
            """;

    private static final String UPSERT_MENTIONS = """
            UNWIND $mentions AS row
            MATCH (node:InfinityKnowledgeGraphNode {key: row.node_key})
            MATCH (revision:InfinityKnowledgeGraphRevision {key: row.revision_key})
            MERGE (node)-[mention:INFINITY_MENTIONED_IN {key: row.key}]->(revision)
            SET mention.tenant_id = row.tenant_id,
                mention.space_id = row.space_id,
                mention.document_id = row.document_id,
                mention.revision_id = row.revision_id,
                mention.chunk_id = row.chunk_id,
                mention.provenance_id = row.provenance_id,
                mention.source_uri = row.source_uri,
                mention.excerpt = row.excerpt,
                mention.confidence = row.confidence
            """;

    private static final String UPSERT_RELATIONS = """
            UNWIND $relations AS row
            MATCH (source:InfinityKnowledgeGraphNode {key: row.source_key})
            MATCH (target:InfinityKnowledgeGraphNode {key: row.target_key})
            MERGE (source)-[relation:INFINITY_KNOWLEDGE_RELATION {key: row.key}]->(target)
            SET relation.tenant_id = row.tenant_id,
                relation.space_id = row.space_id,
                relation.relation_id = row.relation_id,
                relation.type = row.type,
                relation.confidence = row.confidence,
                relation.properties_json = row.properties_json,
                relation.document_id = row.document_id,
                relation.revision_id = row.revision_id,
                relation.chunk_id = row.chunk_id,
                relation.provenance_id = row.provenance_id,
                relation.provenance_confidence = row.provenance_confidence,
                relation.source_uri = row.source_uri,
                relation.excerpt = row.excerpt,
                relation.document_title = row.document_title
            """;

    private final Driver driver;
    private final Neo4jGraphConfig config;
    private final JsonMapper jsonMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean();

    /**
     * Creates a store using an externally managed Neo4j driver.
     */
    public Neo4jGraphStore(
            Driver driver,
            Neo4jGraphConfig config,
            JsonMapper jsonMapper
    ) {
        this.driver = Objects.requireNonNull(driver, "driver must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    @Override
    public void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        synchronized (schemaReady) {
            if (schemaReady.get()) {
                return;
            }
            try (Session session = session()) {
                session.executeWrite(transaction -> {
                    SCHEMA.forEach(statement -> transaction.run(statement).consume());
                    return null;
                });
                schemaReady.set(true);
            } catch (RuntimeException failure) {
                throw failure("Unable to initialize Neo4j knowledge graph schema", failure);
            }
        }
    }

    @Override
    public void replaceRevision(
            KnowledgeDocument document,
            UUID revisionId,
            KnowledgeGraph graph
    ) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
        validateProjection(document, revisionId, graph);
        ensureSchema();

        String revisionKey = revisionKey(document, revisionId);
        String sourceRank = sourceRank(document, revisionId);
        Map<KnowledgeNodeId, String> nodeKeys = nodeKeys(graph);
        Map<String, Object> scope = projectionScope(document, revisionId, revisionKey);
        List<Map<String, Object>> entityRows = graph.entities().stream()
                .map(entity -> entityRow(entity, nodeKeys.get(entity.id()), sourceRank))
                .toList();
        List<Map<String, Object>> eventRows = graph.events().stream()
                .map(event -> eventRow(event, nodeKeys.get(event.id()), sourceRank))
                .toList();
        List<Map<String, Object>> mentionRows = mentionRows(graph, nodeKeys, revisionKey);
        List<Map<String, Object>> relationRows = relationRows(
                graph,
                nodeKeys,
                document.title()
        );

        try (Session session = session()) {
            session.executeWrite(transaction -> {
                transaction.run(UPSERT_REVISION, scope).consume();
                transaction.run(DELETE_REVISION_ASSERTIONS, scope).consume();
                transaction.run(DELETE_REVISION_MENTIONS, scope).consume();
                transaction.run(UPSERT_ENTITIES, Map.of("nodes", entityRows)).consume();
                transaction.run(UPSERT_EVENTS, Map.of("nodes", eventRows)).consume();
                transaction.run(UPSERT_MENTIONS, Map.of("mentions", mentionRows)).consume();
                transaction.run(UPSERT_RELATIONS, Map.of("relations", relationRows)).consume();
                return null;
            });
        } catch (RuntimeException failure) {
            throw failure("Unable to replace Neo4j graph revision", failure);
        }
    }

    @Override
    public List<GraphEvidence> search(GraphQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.accessScope().deniesAll()) {
            return List.of();
        }
        ensureSchema();
        String cypher = searchCypher(query.maxHops());
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", query.accessScope().tenantId().value());
        parameters.put(
                "spaceIds",
                query.accessScope().spaceIds().stream().map(space -> space.value()).toList()
        );
        parameters.put(
                "documentIds",
                query.accessScope().documentIds().stream().sorted().toList()
        );
        parameters.put("restrictDocuments", query.accessScope().restrictsDocuments());
        parameters.put("anchorIds", query.anchorIds().stream()
                .map(KnowledgeNodeId::value)
                .sorted()
                .toList());
        parameters.put("queryText", query.normalizedQuery());
        parameters.put("limit", Math.min(query.limit(), config.maxResults()));
        try (Session session = session()) {
            return session.executeRead(transaction ->
                    transaction.run(cypher, parameters).list(this::graphEvidence)
            );
        } catch (RuntimeException failure) {
            throw failure("Unable to query Neo4j knowledge graph", failure);
        }
    }

    private Session session() {
        return driver.session(SessionConfig.builder().withDatabase(config.database()).build());
    }

    private GraphEvidence graphEvidence(Record row) {
        KnowledgeProvenance provenance = new KnowledgeProvenance(
                UUID.fromString(row.get("provenanceId").asString()),
                new dev.infinityknowledge.domain.identity.TenantId(
                        row.get("tenantId").asString()
                ),
                new dev.infinityknowledge.domain.space.KnowledgeSpaceId(
                        row.get("spaceId").asString()
                ),
                new dev.infinityknowledge.domain.document.DocumentId(
                        UUID.fromString(row.get("documentId").asString())
                ),
                UUID.fromString(row.get("revisionId").asString()),
                UUID.fromString(row.get("chunkId").asString()),
                row.get("sourceUri").asString(),
                row.get("excerpt").asString(),
                row.get("provenanceConfidence").asDouble()
        );
        return new GraphEvidence(
                UUID.fromString(row.get("relationId").asString()),
                new KnowledgeNodeId(row.get("sourceId").asString()),
                row.get("sourceName").asString(),
                row.get("sourceType").asString(),
                new KnowledgeNodeId(row.get("targetId").asString()),
                row.get("targetName").asString(),
                row.get("targetType").asString(),
                row.get("relationType").asString(),
                row.get("documentTitle").asString(),
                provenance,
                row.get("score").asDouble(),
                row.get("depth").asInt()
        );
    }

    private Map<String, Object> projectionScope(
            KnowledgeDocument document,
            UUID revisionId,
            String revisionKey
    ) {
        Map<String, Object> values = new HashMap<>();
        values.put("revisionKey", revisionKey);
        values.put("tenantId", document.tenantId().value());
        values.put("spaceId", document.spaceId().value());
        values.put("documentId", document.id().value().toString());
        values.put("revisionId", revisionId.toString());
        values.put("documentTitle", document.title());
        values.put("sourceUri", document.source().uri());
        values.put("authority", document.authority());
        values.put("updatedAt", document.updatedAt().toString());
        return values;
    }

    private Map<String, Object> entityRow(
            KnowledgeEntity entity,
            String key,
            String sourceRank
    ) {
        Map<String, Object> row = nodeRow(
                key,
                entity.tenantId().value(),
                entity.spaceId().value(),
                entity.id().value(),
                entity.type(),
                entity.name(),
                json(entity.properties())
        );
        row.put("aliases", entity.aliases().stream().sorted().toList());
        row.put("source_rank", sourceRank);
        return row;
    }

    private Map<String, Object> eventRow(
            KnowledgeEvent event,
            String key,
            String sourceRank
    ) {
        Map<String, Object> row = nodeRow(
                key,
                event.tenantId().value(),
                event.spaceId().value(),
                event.id().value(),
                event.type(),
                event.name(),
                json(event.properties())
        );
        row.put("occurred_at", event.occurredAt().toString());
        row.put("source_rank", sourceRank);
        return row;
    }

    private static Map<String, Object> nodeRow(
            String key,
            String tenantId,
            String spaceId,
            String nodeId,
            String type,
            String name,
            String propertiesJson
    ) {
        Map<String, Object> row = new HashMap<>();
        row.put("key", key);
        row.put("tenant_id", tenantId);
        row.put("space_id", spaceId);
        row.put("node_id", nodeId);
        row.put("type", type);
        row.put("name", name);
        row.put("properties_json", propertiesJson);
        row.put("updated_at", java.time.Instant.now().toString());
        return row;
    }

    private static List<Map<String, Object>> mentionRows(
            KnowledgeGraph graph,
            Map<KnowledgeNodeId, String> nodeKeys,
            String revisionKey
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        graph.entities().forEach(entity -> entity.provenance().forEach(provenance ->
                rows.add(mentionRow(nodeKeys.get(entity.id()), revisionKey, provenance))
        ));
        graph.events().forEach(event -> event.provenance().forEach(provenance ->
                rows.add(mentionRow(nodeKeys.get(event.id()), revisionKey, provenance))
        ));
        return rows;
    }

    private static Map<String, Object> mentionRow(
            String nodeKey,
            String revisionKey,
            KnowledgeProvenance provenance
    ) {
        Map<String, Object> row = provenanceRow(provenance);
        row.put("node_key", nodeKey);
        row.put("revision_key", revisionKey);
        row.put("key", scopedKey(nodeKey, provenance.id().toString()));
        return row;
    }

    private List<Map<String, Object>> relationRows(
            KnowledgeGraph graph,
            Map<KnowledgeNodeId, String> nodeKeys,
            String documentTitle
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KnowledgeRelation relation : graph.relations()) {
            for (KnowledgeProvenance provenance : relation.provenance()) {
                Map<String, Object> row = provenanceRow(provenance);
                row.put("key", scopedKey(
                        relation.tenantId().value(),
                        relation.spaceId().value(),
                        relation.id().toString(),
                        provenance.id().toString()
                ));
                row.put("source_key", nodeKeys.get(relation.sourceId()));
                row.put("target_key", nodeKeys.get(relation.targetId()));
                row.put("relation_id", relation.id().toString());
                row.put("type", relation.type());
                row.put("confidence", relation.confidence());
                row.put("properties_json", json(relation.properties()));
                row.put("provenance_confidence", provenance.confidence());
                row.put("document_title", documentTitle);
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> provenanceRow(KnowledgeProvenance provenance) {
        Map<String, Object> row = new HashMap<>();
        row.put("tenant_id", provenance.tenantId().value());
        row.put("space_id", provenance.spaceId().value());
        row.put("document_id", provenance.documentId().value().toString());
        row.put("revision_id", provenance.revisionId().toString());
        row.put("chunk_id", provenance.chunkId().toString());
        row.put("provenance_id", provenance.id().toString());
        row.put("source_uri", provenance.sourceUri());
        row.put("excerpt", provenance.excerpt());
        row.put("confidence", provenance.confidence());
        return row;
    }

    private static Map<KnowledgeNodeId, String> nodeKeys(KnowledgeGraph graph) {
        Map<KnowledgeNodeId, String> keys = new LinkedHashMap<>();
        graph.entities().forEach(entity -> keys.put(
                entity.id(),
                scopedKey(
                        graph.tenantId().value(),
                        graph.spaceId().value(),
                        "ENTITY",
                        entity.id().value()
                )
        ));
        graph.events().forEach(event -> keys.put(
                event.id(),
                scopedKey(
                        graph.tenantId().value(),
                        graph.spaceId().value(),
                        "EVENT",
                        event.id().value()
                )
        ));
        return keys;
    }

    private String json(Map<String, String> properties) {
        try {
            return jsonMapper.writeValueAsString(properties);
        } catch (JacksonException failure) {
            throw new Neo4jGraphStoreException("Unable to serialize graph properties", failure);
        }
    }

    private static String revisionKey(KnowledgeDocument document, UUID revisionId) {
        return scopedKey(
                document.tenantId().value(),
                document.spaceId().value(),
                document.id().value().toString(),
                revisionId.toString()
        );
    }

    private static String sourceRank(KnowledgeDocument document, UUID revisionId) {
        return String.format(
                Locale.ROOT,
                "%03d|%s|%s|%s",
                document.authority(),
                document.updatedAt(),
                document.id().value(),
                revisionId
        );
    }

    private static String scopedKey(String... values) {
        return String.join("\u001F", values);
    }

    private static void validateProjection(
            KnowledgeDocument document,
            UUID revisionId,
            KnowledgeGraph graph
    ) {
        if (!document.tenantId().equals(graph.tenantId())
                || !document.spaceId().equals(graph.spaceId())) {
            throw new IllegalArgumentException("graph scope differs from document scope");
        }
        allProvenance(graph).forEach(provenance -> {
            if (!document.id().equals(provenance.documentId())
                    || !revisionId.equals(provenance.revisionId())) {
                throw new IllegalArgumentException(
                        "graph provenance differs from projected document revision"
                );
            }
        });
    }

    private static Stream<KnowledgeProvenance> allProvenance(KnowledgeGraph graph) {
        return Stream.of(
                graph.entities().stream().flatMap(entity -> entity.provenance().stream()),
                graph.events().stream().flatMap(event -> event.provenance().stream()),
                graph.relations().stream().flatMap(relation -> relation.provenance().stream())
        ).flatMap(stream -> stream);
    }

    private static String searchCypher(int maxHops) {
        return """
                MATCH (anchor:InfinityKnowledgeGraphNode)
                WHERE anchor.tenant_id = $tenantId
                  AND anchor.space_id IN $spaceIds
                  AND (
                    (size($anchorIds) > 0 AND anchor.node_id IN $anchorIds)
                    OR
                    (size($anchorIds) = 0 AND (
                      toLower($queryText) CONTAINS toLower(anchor.name)
                      OR toLower(anchor.name) CONTAINS toLower($queryText)
                      OR any(alias IN coalesce(anchor.aliases, []) WHERE
                        toLower($queryText) CONTAINS toLower(alias)
                        OR toLower(alias) CONTAINS toLower($queryText)
                      )
                    ))
                  )
                MATCH path = (anchor)-[:INFINITY_KNOWLEDGE_RELATION*1..%d]-(related:InfinityKnowledgeGraphNode)
                WHERE related.tenant_id = $tenantId
                  AND related.space_id IN $spaceIds
                WITH path, relationships(path) AS edges
                WHERE all(edge IN edges WHERE
                  edge.tenant_id = $tenantId
                  AND edge.space_id IN $spaceIds
                  AND (NOT $restrictDocuments OR edge.document_id IN $documentIds)
                )
                UNWIND edges AS edge
                WITH edge, min(length(path)) AS depth
                WITH edge, depth, startNode(edge) AS source, endNode(edge) AS target
                WHERE source.tenant_id = $tenantId
                  AND target.tenant_id = $tenantId
                  AND source.space_id IN $spaceIds
                  AND target.space_id IN $spaceIds
                RETURN edge.relation_id AS relationId,
                       source.node_id AS sourceId,
                       source.name AS sourceName,
                       source.type AS sourceType,
                       target.node_id AS targetId,
                       target.name AS targetName,
                       target.type AS targetType,
                       edge.type AS relationType,
                       edge.document_title AS documentTitle,
                       edge.tenant_id AS tenantId,
                       edge.space_id AS spaceId,
                       edge.document_id AS documentId,
                       edge.revision_id AS revisionId,
                       edge.chunk_id AS chunkId,
                       edge.provenance_id AS provenanceId,
                       edge.source_uri AS sourceUri,
                       edge.excerpt AS excerpt,
                       edge.provenance_confidence AS provenanceConfidence,
                       ((edge.confidence + edge.provenance_confidence) / 2.0) / toFloat(depth) AS score,
                       depth
                ORDER BY score DESC, depth ASC, relationId ASC
                LIMIT $limit
                """.formatted(maxHops);
    }

    private static Neo4jGraphStoreException failure(String message, RuntimeException cause) {
        if (cause instanceof Neo4jGraphStoreException storeFailure) {
            return storeFailure;
        }
        return new Neo4jGraphStoreException(message, cause);
    }
}
