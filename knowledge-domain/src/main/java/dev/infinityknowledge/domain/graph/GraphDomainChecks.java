package dev.infinityknowledge.domain.graph;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Package-local validation shared by the small graph model.
 */
final class GraphDomainChecks {

    private GraphDomainChecks() {
    }

    static String type(String value, String field) {
        String normalized = DomainChecks.requiredText(value, field, 64).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    field + " must contain only uppercase letters, digits and underscores"
            );
        }
        return normalized;
    }

    static Set<String> aliases(Set<String> aliases) {
        Objects.requireNonNull(aliases, "aliases must not be null");
        return aliases.stream()
                .map(value -> DomainChecks.requiredText(value, "alias", 512))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static Map<String, String> properties(Map<String, String> properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        Map<String, String> normalized = new LinkedHashMap<>();
        properties.forEach((key, value) -> normalized.put(
                DomainChecks.requiredText(key, "property key", 128),
                DomainChecks.requiredText(value, "property value", 8_192)
        ));
        return Map.copyOf(normalized);
    }
}
