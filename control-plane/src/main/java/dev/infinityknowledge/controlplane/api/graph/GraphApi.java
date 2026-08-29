package dev.infinityknowledge.controlplane.api.graph;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Stable HTTP DTOs for the tenant-scoped graph explorer. */
public final class GraphApi {
    private GraphApi() {
    }

    public record SearchRequest(
            @NotBlank @Size(max = 16_000) String query,
            @Size(max = 100) Set<@NotBlank @Size(max = 128) String> spaceIds,
            int maxHops,
            int limit
    ) {
        public SearchRequest {
            spaceIds = spaceIds == null ? Set.of() : Set.copyOf(spaceIds);
            maxHops = maxHops == 0 ? 2 : maxHops;
            limit = limit == 0 ? 50 : limit;
            if (maxHops < 1 || maxHops > 3) {
                throw new IllegalArgumentException("maxHops must be between 1 and 3");
            }
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("limit must be between 1 and 200");
            }
        }
    }

    public record SearchResponse(String tenantId, List<Edge> edges) {
        public SearchResponse {
            edges = List.copyOf(edges);
        }
    }

    public record Edge(
            UUID relationId,
            Node source,
            Node target,
            String type,
            int depth,
            double score,
            Provenance provenance
    ) {
    }

    public record Node(String id, String type, String name) {
    }

    public record Provenance(
            UUID documentId,
            UUID revisionId,
            UUID chunkId,
            String documentTitle,
            String sourceUri,
            String excerpt,
            double confidence
    ) {
    }
}
