package dev.infinityknowledge.store.neo4j;

import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.graph.KnowledgeGraph;
import dev.infinityknowledge.domain.graph.KnowledgeProvenance;
import dev.infinityknowledge.spi.graph.GraphExtractor;
import dev.infinityknowledge.spi.graph.GraphStore;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionType;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Extracts and publishes one immutable document revision to the graph store.
 */
public final class Neo4jGraphProjectionExecutor implements ProjectionExecutor {

    private final GraphExtractor graphExtractor;
    private final GraphStore graphStore;
    private final ActiveRevisionGuard activeRevisionGuard;

    /**
     * Creates the idempotent graph projection executor.
     */
    public Neo4jGraphProjectionExecutor(
            GraphExtractor graphExtractor,
            GraphStore graphStore,
            ActiveRevisionGuard activeRevisionGuard
    ) {
        this.graphExtractor = Objects.requireNonNull(
                graphExtractor,
                "graphExtractor must not be null"
        );
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore must not be null");
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
    }

    @Override
    public ProjectionType projectionType() {
        return ProjectionType.GRAPH;
    }

    @Override
    public void project(ProjectionSource source) {
        Objects.requireNonNull(source, "source must not be null");
        UUID revisionId = singleRevision(source.chunks());
        if (!isActive(source, revisionId)) {
            return;
        }

        KnowledgeGraph graph = graphExtractor.extract(source);
        validateExtraction(source, revisionId, graph);

        // Extraction may involve a remote model, so protect the write from a head change.
        if (isActive(source, revisionId)) {
            graphStore.replaceRevision(source.document(), revisionId, graph);
        }
    }

    private boolean isActive(ProjectionSource source, UUID revisionId) {
        return activeRevisionGuard.isActive(
                source.document().tenantId(),
                source.document().id(),
                revisionId
        );
    }

    private static UUID singleRevision(List<KnowledgeChunk> chunks) {
        UUID revisionId = chunks.getFirst().revisionId();
        if (chunks.stream().anyMatch(chunk -> !revisionId.equals(chunk.revisionId()))) {
            throw new IllegalArgumentException("graph projection source mixes revisions");
        }
        return revisionId;
    }

    private static void validateExtraction(
            ProjectionSource source,
            UUID revisionId,
            KnowledgeGraph graph
    ) {
        Objects.requireNonNull(graph, "graphExtractor returned null");
        if (!source.document().tenantId().equals(graph.tenantId())
                || !source.document().spaceId().equals(graph.spaceId())) {
            throw new IllegalArgumentException("graph scope differs from projection source");
        }
        Set<UUID> chunkIds = source.chunks().stream()
                .map(KnowledgeChunk::id)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        allProvenance(graph).forEach(provenance -> validateProvenance(
                source,
                revisionId,
                chunkIds,
                provenance
        ));
    }

    private static Stream<KnowledgeProvenance> allProvenance(KnowledgeGraph graph) {
        Stream<KnowledgeProvenance> entities = graph.entities().stream()
                .flatMap(entity -> entity.provenance().stream());
        Stream<KnowledgeProvenance> events = graph.events().stream()
                .flatMap(event -> event.provenance().stream());
        Stream<KnowledgeProvenance> relations = graph.relations().stream()
                .flatMap(relation -> relation.provenance().stream());
        return Stream.concat(Stream.concat(entities, events), relations);
    }

    private static void validateProvenance(
            ProjectionSource source,
            UUID revisionId,
            Set<UUID> chunkIds,
            KnowledgeProvenance provenance
    ) {
        if (!source.document().tenantId().equals(provenance.tenantId())
                || !source.document().spaceId().equals(provenance.spaceId())
                || !source.document().id().equals(provenance.documentId())
                || !revisionId.equals(provenance.revisionId())
                || !chunkIds.contains(provenance.chunkId())) {
            throw new IllegalArgumentException(
                    "graph provenance must reference a chunk in the projected revision"
            );
        }
    }
}
