package dev.infinityknowledge.spi.connector;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.Map;
import java.util.Objects;

/**
 * Describes one active external source without exposing its persistence format.
 *
 * @param connectorId stable connector identifier
 * @param spaceId target knowledge space
 * @param type provider type
 * @param displayName operator-facing name
 * @param authority authority assigned to ingested documents
 * @param configuration provider-specific, non-secret settings
 */
public record SourceConnectorDefinition(
        String connectorId,
        KnowledgeSpaceId spaceId,
        String type,
        String displayName,
        int authority,
        Map<String, String> configuration
) {

    public SourceConnectorDefinition {
        connectorId = DomainChecks.requiredText(connectorId, "connectorId", 128);
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        type = DomainChecks.requiredText(type, "type", 64).toUpperCase(java.util.Locale.ROOT);
        displayName = DomainChecks.requiredText(displayName, "displayName", 256);
        if (authority < 0 || authority > 100) {
            throw new IllegalArgumentException("authority must be between 0 and 100");
        }
        configuration = Map.copyOf(
                Objects.requireNonNull(configuration, "configuration must not be null")
        );
    }
}
