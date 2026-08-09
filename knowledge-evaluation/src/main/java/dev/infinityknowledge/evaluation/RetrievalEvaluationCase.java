package dev.infinityknowledge.evaluation;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One immutable retrieval benchmark case.
 *
 * @param id case identifier
 * @param query authenticated query
 * @param expectedDocuments relevant documents
 * @param expectedChunks optional more precise chunk labels
 */
public record RetrievalEvaluationCase(
        UUID id,
        KnowledgeQuery query,
        Set<DocumentId> expectedDocuments,
        Set<UUID> expectedChunks
) {

    /**
     * Requires at least one relevance label.
     */
    public RetrievalEvaluationCase {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(query, "query must not be null");
        expectedDocuments = Set.copyOf(Objects.requireNonNull(
                expectedDocuments,
                "expectedDocuments must not be null"
        ));
        expectedChunks = Set.copyOf(Objects.requireNonNull(
                expectedChunks,
                "expectedChunks must not be null"
        ));
        if (expectedDocuments.isEmpty() && expectedChunks.isEmpty()) {
            throw new IllegalArgumentException("evaluation case must contain relevance labels");
        }
    }
}
