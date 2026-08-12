package dev.infinityknowledge.domain.wiki;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, provenance-bound content revision of a knowledge page. */
public record KnowledgePageRevision(
        UUID id,
        KnowledgePageId pageId,
        long revisionNumber,
        String summary,
        String markdown,
        List<PageSourceReference> sources,
        String contentHash,
        String compilerVersion,
        String generatedBy,
        Instant createdAt
) {

    public KnowledgePageRevision {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(pageId, "pageId must not be null");
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("revisionNumber must be positive");
        }
        summary = DomainChecks.requiredText(summary, "summary", 8_000);
        markdown = DomainChecks.requiredText(markdown, "markdown", 2_000_000);
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("compiled page revision requires provenance");
        }
        contentHash = DomainChecks.requiredText(contentHash, "contentHash", 128);
        compilerVersion = DomainChecks.requiredText(
                compilerVersion,
                "compilerVersion",
                128
        );
        generatedBy = DomainChecks.requiredText(generatedBy, "generatedBy", 128);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Highest source authority; generated content may never exceed this value. */
    public int maximumSourceAuthority() {
        return sources.stream().mapToInt(PageSourceReference::authority).max().orElseThrow();
    }
}
