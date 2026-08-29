package dev.infinityknowledge.controlplane.api.governance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 租户管理 API 对外提供的只读视图。
 */
public final class ManagementViews {

    private ManagementViews() {
    }

    public record Overview(
            long spaces,
            long activeDocuments,
            long chunks,
            long connectors,
            long pendingProjections,
            long deadProjections,
            long evaluationDatasets,
            long evaluationRuns
    ) {
    }

    public record Space(
            String id,
            String name,
            String description,
            String status,
            long version,
            long documentCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record Document(
            UUID id,
            String spaceId,
            String title,
            String sourceType,
            String sourceUri,
            String status,
            int authority,
            long version,
            UUID activeRevisionId,
            long chunkCount,
            String keywordStatus,
            String vectorStatus,
            String graphStatus,
            String originalFileName,
            String sourceMediaType,
            Long sourceContentLength,
            Instant updatedAt
    ) {
    }

    public record Chunk(
            UUID id,
            int ordinal,
            List<String> sectionPath,
            String content,
            String contentHash
    ) {
        public Chunk {
            sectionPath = List.copyOf(sectionPath);
        }
    }

    public record Revision(
            UUID revisionId,
            long revisionNumber,
            String contentHash,
            String mediaType,
            String language,
            String parserVersion,
            Instant createdAt,
            boolean active,
            long chunkCount
    ) {
    }

    public record Connector(
            String id,
            String spaceId,
            String type,
            String displayName,
            String status,
            long version,
            UUID lastRunId,
            String lastRunStatus,
            Instant lastRunAt,
            Instant updatedAt
    ) {
    }

    public record Trace(
            UUID id,
            UUID requestId,
            String principalId,
            long totalDurationMs,
            int resultCount,
            Instant createdAt,
            List<TraceStep> steps
    ) {
        public Trace {
            steps = List.copyOf(steps);
        }
    }

    public record TraceStep(
            int ordinal,
            String name,
            long durationMs,
            int inputCount,
            int outputCount,
            String status
    ) {
    }

    public record Page<T>(
            List<T> items,
            int limit,
            int offset,
            long total
    ) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record Health(
            String status,
            Map<String, String> capabilities
    ) {
        public Health {
            capabilities = Map.copyOf(capabilities);
        }
    }
}
