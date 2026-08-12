package dev.infinityknowledge.store.neo4j;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.spi.graph.GraphEvidence;
import dev.infinityknowledge.spi.graph.GraphQuery;
import dev.infinityknowledge.spi.graph.GraphStore;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import dev.infinityknowledge.spi.retrieval.Retriever;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ACL-aware graph retrieval channel backed by a {@link GraphStore}.
 */
public final class Neo4jGraphRetriever implements Retriever {

    private final GraphStore graphStore;
    private final ActiveRevisionGuard activeRevisionGuard;
    private final Neo4jGraphConfig config;

    /**
     * Creates the graph retriever.
     */
    public Neo4jGraphRetriever(
            GraphStore graphStore,
            ActiveRevisionGuard activeRevisionGuard,
            Neo4jGraphConfig config
    ) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore must not be null");
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public RetrievalChannel channel() {
        return RetrievalChannel.GRAPH;
    }

    @Override
    public List<RetrievalCandidate> retrieve(RetrievalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.accessScope().deniesAll()) {
            return List.of();
        }
        int limit = Math.min(request.plan().candidateLimit(), config.maxResults());
        List<GraphEvidence> evidence = traverseActiveGraph(request, limit);
        Map<UUID, List<GraphEvidence>> byChunk = evidence.stream().collect(
                Collectors.groupingBy(
                        fact -> fact.provenance().chunkId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                )
        );
        List<RetrievalCandidate> candidates = new ArrayList<>(byChunk.size());
        int rank = 1;
        for (List<GraphEvidence> chunkFacts : byChunk.values()) {
            if (candidates.size() >= limit) {
                break;
            }
            GraphEvidence fact = chunkFacts.getFirst();
            candidates.add(new RetrievalCandidate(
                    fact.provenance().chunkId(),
                    fact.provenance().tenantId(),
                    fact.provenance().spaceId(),
                    fact.provenance().documentId(),
                    fact.provenance().revisionId(),
                    RetrievalChannel.GRAPH,
                    rank++,
                    fact.score(),
                    fact.documentTitle(),
                    List.of("Knowledge Graph", fact.sourceName()),
                    graphContent(chunkFacts),
                    fact.provenance().sourceUri(),
                    Map.of(
                            "retriever", "neo4j",
                            "relationIds", chunkFacts.stream()
                                    .map(item -> item.relationId().toString())
                                    .distinct()
                                    .collect(Collectors.joining(",")),
                            "relationCount", Integer.toString(chunkFacts.size()),
                            "sourceNodeId", fact.sourceId().value(),
                            "targetNodeId", fact.targetId().value(),
                            "depth", Integer.toString(fact.depth())
                    )
            ));
        }
        return activeRevisionGuard.retainActive(
                request.accessScope().tenantId(),
                candidates
        );
    }

    private List<GraphEvidence> traverseActiveGraph(
            RetrievalRequest request,
            int candidateLimit
    ) {
        Map<String, GraphEvidence> accepted = new LinkedHashMap<>();
        Set<KnowledgeNodeId> visited = new HashSet<>();
        Set<KnowledgeNodeId> frontier = Set.of();
        for (int depth = 1; depth <= config.maxHops(); depth++) {
            Set<KnowledgeNodeId> anchors = frontier.stream()
                    .filter(visited::add)
                    .collect(Collectors.toUnmodifiableSet());
            if (depth > 1 && anchors.isEmpty()) {
                break;
            }
            List<GraphEvidence> active = retainActive(graphStore.search(new GraphQuery(
                    request.plan().normalizedQuery(),
                    anchors,
                    request.accessScope(),
                    1,
                    config.maxResults()
            )), request);
            Set<KnowledgeNodeId> next = new HashSet<>();
            for (GraphEvidence fact : active) {
                GraphEvidence adjusted = withDepth(fact, depth);
                accepted.putIfAbsent(evidenceKey(adjusted), adjusted);
                next.add(fact.sourceId());
                next.add(fact.targetId());
            }
            if (accepted.size() >= config.maxResults() || active.isEmpty()) {
                break;
            }
            frontier = Set.copyOf(next);
        }
        return accepted.values().stream().limit(candidateLimit * 4L).toList();
    }

    private List<GraphEvidence> retainActive(
            List<GraphEvidence> evidence,
            RetrievalRequest request
    ) {
        List<RetrievalCandidate> markers = new ArrayList<>(evidence.size());
        int rank = 1;
        for (GraphEvidence fact : evidence) {
            markers.add(marker(fact, rank++));
        }
        Set<ProvenanceKey> activeProvenance = activeRevisionGuard.retainActive(
                request.accessScope().tenantId(),
                markers
        ).stream()
                .map(candidate -> new ProvenanceKey(
                        candidate.documentId(),
                        candidate.revisionId(),
                        candidate.chunkId()
                ))
                .collect(Collectors.toUnmodifiableSet());
        return evidence.stream()
                .filter(fact -> activeProvenance.contains(new ProvenanceKey(
                        fact.provenance().documentId(),
                        fact.provenance().revisionId(),
                        fact.provenance().chunkId()
                )))
                .toList();
    }

    private static RetrievalCandidate marker(GraphEvidence fact, int rank) {
        return new RetrievalCandidate(
                fact.provenance().chunkId(),
                fact.provenance().tenantId(),
                fact.provenance().spaceId(),
                fact.provenance().documentId(),
                fact.provenance().revisionId(),
                RetrievalChannel.GRAPH,
                rank,
                fact.score(),
                fact.documentTitle(),
                List.of("Knowledge Graph"),
                fact.sourceName() + " --" + fact.relationType() + "--> " + fact.targetName(),
                fact.provenance().sourceUri(),
                Map.of("retriever", "neo4j-active-revision-check")
        );
    }

    private static GraphEvidence withDepth(GraphEvidence fact, int depth) {
        return new GraphEvidence(
                fact.relationId(),
                fact.sourceId(),
                fact.sourceName(),
                fact.sourceType(),
                fact.targetId(),
                fact.targetName(),
                fact.targetType(),
                fact.relationType(),
                fact.documentTitle(),
                fact.provenance(),
                fact.score() / depth,
                depth
        );
    }

    private static String evidenceKey(GraphEvidence fact) {
        return fact.relationId() + ":" + fact.provenance().id();
    }

    private static String graphContent(List<GraphEvidence> facts) {
        StringBuilder content = new StringBuilder();
        facts.stream()
                .map(fact -> fact.sourceName() + " --" + fact.relationType() + "--> "
                        + fact.targetName() + '.')
                .distinct()
                .forEach(fact -> {
                    if (!content.isEmpty()) {
                        content.append(' ');
                    }
                    content.append(fact);
                });
        content.append(" Evidence: ").append(facts.getFirst().provenance().excerpt());
        return content.length() <= 100_000
                ? content.toString()
                : content.substring(0, 100_000);
    }

    private record ProvenanceKey(
            DocumentId documentId,
            UUID revisionId,
            UUID chunkId
    ) {
    }
}
