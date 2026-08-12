package dev.infinityknowledge.controlplane.api;

import dev.infinityknowledge.domain.wiki.KnowledgePageStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Requests and views for governed Wiki knowledge pages. */
public final class WikiApi {
    private WikiApi() {
    }

    public record CompileRequest(
            @NotBlank @Size(max = 64) String spaceId,
            @NotBlank @Size(max = 256) String slug,
            @NotBlank @Size(max = 512) String title,
            @NotEmpty @Size(max = 100) List<@Valid SourceSelection> sources
    ) {
        public CompileRequest {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    public record SourceSelection(
            @NotNull UUID documentId,
            @NotNull UUID revisionId,
            @NotNull UUID chunkId
    ) {
    }

    public record TransitionRequest(@Min(0) long expectedVersion) {
    }

    public record PageSummary(
            UUID id,
            String spaceId,
            String slug,
            String title,
            KnowledgePageStatus status,
            UUID latestRevisionId,
            UUID activeRevisionId,
            long version,
            int sourceCount,
            Instant updatedAt
    ) {
    }

    public record PageDetail(
            PageSummary page,
            Revision latestRevision,
            Revision activeRevision,
            boolean unpublishedChanges
    ) {
    }

    public record Revision(
            UUID id,
            long revisionNumber,
            String summary,
            String markdown,
            List<SourceReference> sources,
            String contentHash,
            String compilerVersion,
            String generatedBy,
            Instant createdAt
    ) {
        public Revision {
            sources = List.copyOf(sources);
        }
    }

    public record SourceReference(
            UUID documentId,
            UUID revisionId,
            UUID chunkId,
            List<String> sectionPath,
            String contentHash,
            int authority
    ) {
        public SourceReference {
            sectionPath = List.copyOf(sectionPath);
        }
    }
}
