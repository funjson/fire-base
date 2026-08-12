package dev.infinityknowledge.spi.objectstorage;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.SourceObjectReference;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Objects;

/** Active revision's original object together with its authorization scope. */
public record DocumentSourceObject(
        DocumentId documentId,
        KnowledgeSpaceId spaceId,
        DocumentStatus documentStatus,
        SourceObjectReference sourceObject
) {

    /** Rejects incomplete catalog projections. */
    public DocumentSourceObject {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentStatus, "documentStatus must not be null");
        Objects.requireNonNull(sourceObject, "sourceObject must not be null");
    }
}
