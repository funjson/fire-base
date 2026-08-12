package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Resource limits for synchronous rich-document ingestion. */
@ConfigurationProperties(prefix = "infinity.knowledge.ingestion.files")
public record FileIngestionProperties(
        int maximumSourceBytes,
        long maximumExpandedBytes,
        int maximumPages,
        int maximumElements,
        int maximumTextCharacters,
        int maximumArchiveEntries,
        int maximumCompressionRatio
) {

    /** Reuses the parser's validated budget model. */
    public FileIngestionProperties {
        limits(
                maximumSourceBytes,
                maximumExpandedBytes,
                maximumPages,
                maximumElements,
                maximumTextCharacters,
                maximumArchiveEntries,
                maximumCompressionRatio
        );
    }

    /** Returns immutable parser limits. */
    public DocumentParseLimits limits() {
        return limits(
                maximumSourceBytes,
                maximumExpandedBytes,
                maximumPages,
                maximumElements,
                maximumTextCharacters,
                maximumArchiveEntries,
                maximumCompressionRatio
        );
    }

    private static DocumentParseLimits limits(
            int maximumSourceBytes,
            long maximumExpandedBytes,
            int maximumPages,
            int maximumElements,
            int maximumTextCharacters,
            int maximumArchiveEntries,
            int maximumCompressionRatio
    ) {
        return new DocumentParseLimits(
                maximumSourceBytes,
                maximumExpandedBytes,
                maximumPages,
                maximumElements,
                maximumTextCharacters,
                maximumArchiveEntries,
                maximumCompressionRatio
        );
    }
}
