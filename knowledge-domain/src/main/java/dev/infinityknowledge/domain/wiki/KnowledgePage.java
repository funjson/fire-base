package dev.infinityknowledge.domain.wiki;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable governance aggregate for a compiled knowledge page. */
public record KnowledgePage(
        KnowledgePageId id,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        String slug,
        String title,
        KnowledgePageStatus status,
        UUID latestRevisionId,
        UUID activeRevisionId,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public KnowledgePage {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        slug = DomainChecks.requiredText(slug, "slug", 256);
        if (!slug.matches("[a-z0-9]+(?:[a-z0-9._-]*[a-z0-9])?")) {
            throw new IllegalArgumentException("slug must be a lowercase URL-safe identifier");
        }
        title = DomainChecks.requiredText(title, "title", 512);
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(latestRevisionId, "latestRevisionId must not be null");
        if (status == KnowledgePageStatus.PUBLISHED && activeRevisionId == null) {
            throw new IllegalArgumentException("published page requires an active revision");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
