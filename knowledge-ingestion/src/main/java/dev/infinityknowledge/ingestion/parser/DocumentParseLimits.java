package dev.infinityknowledge.ingestion.parser;

/**
 * Resource budgets applied before and during rich-document parsing.
 *
 * @param maximumSourceBytes maximum compressed/source byte count
 * @param maximumExpandedBytes maximum cumulative uncompressed archive bytes
 * @param maximumPages maximum PDF page count
 * @param maximumElements maximum emitted structural elements
 * @param maximumTextCharacters maximum emitted text characters
 * @param maximumArchiveEntries maximum entries in an Office archive
 * @param maximumCompressionRatio maximum accepted expanded/compressed entry ratio
 */
public record DocumentParseLimits(
        int maximumSourceBytes,
        long maximumExpandedBytes,
        int maximumPages,
        int maximumElements,
        int maximumTextCharacters,
        int maximumArchiveEntries,
        int maximumCompressionRatio
) {

    /** Validates all parsing budgets. */
    public DocumentParseLimits {
        if (maximumSourceBytes < 1) {
            throw new IllegalArgumentException("maximumSourceBytes must be positive");
        }
        if (maximumExpandedBytes < maximumSourceBytes) {
            throw new IllegalArgumentException("maximumExpandedBytes must cover maximumSourceBytes");
        }
        if (maximumPages < 1 || maximumElements < 1 || maximumTextCharacters < 1
                || maximumArchiveEntries < 1 || maximumCompressionRatio < 1) {
            throw new IllegalArgumentException("all parser budgets must be positive");
        }
    }

    /** Conservative enterprise defaults suitable for synchronous ingestion workers. */
    public static DocumentParseLimits defaults() {
        return new DocumentParseLimits(
                25 * 1_024 * 1_024,
                100L * 1_024 * 1_024,
                500,
                20_000,
                10_000_000,
                10_000,
                100
        );
    }
}
