package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Applies shared structural budgets while creating deterministic knowledge elements. */
final class ElementAccumulator {

    private static final int MAXIMUM_ELEMENT_CHARACTERS = 1_900_000;

    private final UUID revisionId;
    private final DocumentParseLimits limits;
    private final List<KnowledgeElement> elements = new ArrayList<>();
    private final List<String> headings = new ArrayList<>();
    private int textCharacters;

    ElementAccumulator(UUID revisionId, DocumentParseLimits limits) {
        this.revisionId = revisionId;
        this.limits = limits;
    }

    /** Adds a heading and updates the section path used by following elements. */
    void heading(int level, String title, Map<String, String> attributes) {
        if (level < 1 || level > 6) {
            throw new DocumentParseException("heading level must be between 1 and 6");
        }
        String normalized = normalize(title);
        if (normalized.isEmpty()) {
            return;
        }
        if (normalized.length() > 512) {
            throw new DocumentParseException("heading exceeds 512 characters");
        }
        while (headings.size() >= level) {
            headings.removeLast();
        }
        headings.add(normalized);
        add(ElementType.HEADING, normalized, attributes);
    }

    /** Adds text, splitting only when the domain's per-element safety limit requires it. */
    void add(ElementType type, String content, Map<String, String> attributes) {
        String normalized = normalize(content);
        if (normalized.isEmpty()) {
            return;
        }
        int offset = 0;
        while (offset < normalized.length()) {
            int end = Math.min(offset + MAXIMUM_ELEMENT_CHARACTERS, normalized.length());
            addSingle(type, normalized.substring(offset, end), attributes);
            offset = end;
        }
    }

    /** Returns immutable elements in source order. */
    List<KnowledgeElement> elements() {
        return List.copyOf(elements);
    }

    private void addSingle(ElementType type, String content, Map<String, String> attributes) {
        if (elements.size() >= limits.maximumElements()) {
            throw new DocumentParseException("document exceeds maximumElements");
        }
        if ((long) textCharacters + content.length() > limits.maximumTextCharacters()) {
            throw new DocumentParseException("document exceeds maximumTextCharacters");
        }
        int ordinal = elements.size();
        String identity = revisionId + ":" + ordinal + ':' + type + ':' + content;
        elements.add(new KnowledgeElement(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                revisionId,
                null,
                type,
                ordinal,
                List.copyOf(headings),
                content,
                new LinkedHashMap<>(attributes)
        ));
        textCharacters += content.length();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
