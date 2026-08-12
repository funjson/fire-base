package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.GraphApi;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.graph.KnowledgeNodeId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.graph.GraphEvidence;
import dev.infinityknowledge.spi.graph.GraphQuery;
import dev.infinityknowledge.spi.graph.GraphStore;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Authorization and active-revision boundary for graph inspection. */
@Service
public final class GraphApplicationService {
    private final AccessPolicy accessPolicy;
    private final Optional<GraphStore> graphStore;
    private final ActiveRevisionGuard activeRevisionGuard;

    public GraphApplicationService(
            AccessPolicy accessPolicy,
            Optional<GraphStore> graphStore,
            ActiveRevisionGuard activeRevisionGuard
    ) {
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy,
                "accessPolicy must not be null"
        );
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore must not be null");
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
    }

    public GraphApi.SearchResponse search(
            PrincipalContext principal,
            GraphApi.SearchRequest request
    ) {
        requireAdmin(principal);
        Set<KnowledgeSpaceId> requestedSpaces = request.spaceIds().stream()
                .map(KnowledgeSpaceId::new)
                .collect(Collectors.toUnmodifiableSet());
        var scope = accessPolicy.resolve(principal, requestedSpaces);
        if (!principal.tenantId().equals(scope.tenantId())) {
            throw new SecurityException("access policy returned a different tenant");
        }
        if (scope.deniesAll()) {
            throw new KnowledgeAccessDeniedException("No readable knowledge space");
        }
        var edges = traverseActiveGraph(
                requiredGraphStore(),
                principal,
                request.query().replaceAll("\\s+", " ").strip(),
                scope,
                request.maxHops(),
                request.limit()
        ).stream()
                .map(GraphApplicationService::edge)
                .toList();
        return new GraphApi.SearchResponse(principal.tenantId().value(), edges);
    }

    private List<GraphEvidence> traverseActiveGraph(
            GraphStore store,
            PrincipalContext principal,
            String normalizedQuery,
            AccessScope scope,
            int maxHops,
            int limit
    ) {
        Map<String, GraphEvidence> accepted = new LinkedHashMap<>();
        Set<KnowledgeNodeId> visited = new HashSet<>();
        Set<KnowledgeNodeId> frontier = Set.of();
        for (int depth = 1; depth <= maxHops && accepted.size() < limit; depth++) {
            Set<KnowledgeNodeId> anchors = frontier.stream()
                    .filter(visited::add)
                    .collect(Collectors.toUnmodifiableSet());
            if (depth > 1 && anchors.isEmpty()) {
                break;
            }
            List<GraphEvidence> active = retainActive(store.search(new GraphQuery(
                    normalizedQuery,
                    anchors,
                    scope,
                    1,
                    limit
            )), principal.tenantId());
            if (active.isEmpty()) {
                break;
            }
            Set<KnowledgeNodeId> next = new HashSet<>();
            for (GraphEvidence value : active) {
                GraphEvidence adjusted = withDepth(value, depth);
                accepted.putIfAbsent(evidenceKey(adjusted), adjusted);
                next.add(value.sourceId());
                next.add(value.targetId());
            }
            frontier = Set.copyOf(next);
        }
        return retainActive(
                accepted.values().stream().limit(limit).toList(),
                principal.tenantId()
        );
    }

    private List<GraphEvidence> retainActive(
            List<GraphEvidence> evidence,
            TenantId tenantId
    ) {
        List<RetrievalCandidate> markers = new ArrayList<>(evidence.size());
        int rank = 1;
        for (GraphEvidence value : evidence) {
            markers.add(marker(value, rank++));
        }
        Set<ProvenanceKey> active = activeRevisionGuard.retainActive(
                tenantId,
                markers
        ).stream()
                .map(candidate -> new ProvenanceKey(
                        candidate.documentId(),
                        candidate.revisionId(),
                        candidate.chunkId()
                ))
                .collect(Collectors.toUnmodifiableSet());
        return evidence.stream()
                .filter(value -> active.contains(new ProvenanceKey(
                        value.provenance().documentId(),
                        value.provenance().revisionId(),
                        value.provenance().chunkId()
                )))
                .toList();
    }

    private static RetrievalCandidate marker(GraphEvidence value, int rank) {
        var provenance = value.provenance();
        return new RetrievalCandidate(
                provenance.chunkId(),
                provenance.tenantId(),
                provenance.spaceId(),
                provenance.documentId(),
                provenance.revisionId(),
                RetrievalChannel.GRAPH,
                rank,
                value.score(),
                value.documentTitle(),
                List.of("Knowledge Graph"),
                value.sourceName() + " --" + value.relationType() + "--> "
                        + value.targetName(),
                provenance.sourceUri(),
                Map.of("retriever", "neo4j-active-revision-check")
        );
    }

    private static GraphEvidence withDepth(GraphEvidence value, int depth) {
        return new GraphEvidence(
                value.relationId(),
                value.sourceId(),
                value.sourceName(),
                value.sourceType(),
                value.targetId(),
                value.targetName(),
                value.targetType(),
                value.relationType(),
                value.documentTitle(),
                value.provenance(),
                value.score() / depth,
                depth
        );
    }

    private static String evidenceKey(GraphEvidence value) {
        return value.relationId() + ":" + value.provenance().id();
    }

    private GraphStore requiredGraphStore() {
        return graphStore.orElseThrow(GraphCapabilityUnavailableException::new);
    }

    private static GraphApi.Edge edge(GraphEvidence value) {
        var provenance = value.provenance();
        return new GraphApi.Edge(
                value.relationId(),
                new GraphApi.Node(
                        value.sourceId().value(),
                        value.sourceType(),
                        value.sourceName()
                ),
                new GraphApi.Node(
                        value.targetId().value(),
                        value.targetType(),
                        value.targetName()
                ),
                value.relationType(),
                value.depth(),
                value.score(),
                new GraphApi.Provenance(
                        provenance.documentId().value(),
                        provenance.revisionId(),
                        provenance.chunkId(),
                        value.documentTitle(),
                        provenance.sourceUri(),
                        provenance.excerpt(),
                        provenance.confidence()
                )
        );
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }

    private record ProvenanceKey(
            DocumentId documentId,
            UUID revisionId,
            UUID chunkId
    ) {
    }
}
