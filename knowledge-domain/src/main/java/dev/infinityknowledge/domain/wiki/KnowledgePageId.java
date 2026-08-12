package dev.infinityknowledge.domain.wiki;

import java.util.Objects;
import java.util.UUID;

/** Stable identifier of one compiled knowledge page. */
public record KnowledgePageId(UUID value) {

    public KnowledgePageId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
