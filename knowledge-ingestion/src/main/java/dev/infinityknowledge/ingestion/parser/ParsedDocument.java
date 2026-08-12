package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.KnowledgeElement;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structural parser output ready for chunking.
 *
 * @param parserId stable parser implementation identifier
 * @param parserVersion parser contract version persisted in the revision fingerprint
 * @param elements ordered structural elements
 * @param attributes non-sensitive document-level attributes
 */
public record ParsedDocument(
        String parserId,
        String parserVersion,
        List<KnowledgeElement> elements,
        Map<String, String> attributes
) {

    /** Defensively copies parser output. */
    public ParsedDocument {
        Objects.requireNonNull(parserId, "parserId must not be null");
        Objects.requireNonNull(parserVersion, "parserVersion must not be null");
        elements = List.copyOf(Objects.requireNonNull(elements, "elements must not be null"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
    }
}
