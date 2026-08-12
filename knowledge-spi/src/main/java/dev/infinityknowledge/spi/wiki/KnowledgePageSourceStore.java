package dev.infinityknowledge.spi.wiki;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Loads exact immutable source chunks selected for one page compilation. */
@FunctionalInterface
public interface KnowledgePageSourceStore {

    List<LoadedSource> load(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            List<SourceSelection> selections
    );

    record SourceSelection(DocumentId documentId, UUID revisionId, UUID chunkId) {
        public SourceSelection {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(revisionId, "revisionId must not be null");
            Objects.requireNonNull(chunkId, "chunkId must not be null");
        }
    }

    record LoadedSource(KnowledgeChunk chunk, int authority) {
        public LoadedSource {
            Objects.requireNonNull(chunk, "chunk must not be null");
            if (authority < 0 || authority > 100) {
                throw new IllegalArgumentException("authority must be between 0 and 100");
            }
        }
    }
}
