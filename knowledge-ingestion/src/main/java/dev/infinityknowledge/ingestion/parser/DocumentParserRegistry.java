package dev.infinityknowledge.ingestion.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic media-type and extension registry for structural document parsers.
 */
public final class DocumentParserRegistry {

    private final Map<String, DocumentParser> mediaTypeParsers;
    private final Map<String, DocumentParser> extensionParsers;

    /** Builds an immutable registry and rejects ambiguous parser ownership. */
    public DocumentParserRegistry(Collection<? extends DocumentParser> parsers) {
        Objects.requireNonNull(parsers, "parsers must not be null");
        Map<String, DocumentParser> byMediaType = new LinkedHashMap<>();
        Map<String, DocumentParser> byExtension = new LinkedHashMap<>();
        for (DocumentParser parser : parsers) {
            Objects.requireNonNull(parser, "parser must not be null");
            parser.supportedMediaTypes().forEach(mediaType -> putUnique(
                    byMediaType,
                    normalizeMediaType(mediaType),
                    parser,
                    "media type"
            ));
            parser.supportedExtensions().forEach(extension -> putUnique(
                    byExtension,
                    normalizeExtension(extension),
                    parser,
                    "extension"
            ));
        }
        mediaTypeParsers = Map.copyOf(byMediaType);
        extensionParsers = Map.copyOf(byExtension);
    }

    /** Creates the built-in text, HTML, PDF and DOCX registry. */
    public static DocumentParserRegistry standard() {
        return new DocumentParserRegistry(java.util.List.of(
                new PlainTextDocumentParser(),
                new HtmlDocumentParser(),
                new PdfDocumentParser(),
                new DocxDocumentParser()
        ));
    }

    /**
     * Selects one parser, reads at most the configured source budget and parses it.
     *
     * @param declaredLength exact source length, or {@code -1} when unknown
     */
    public ParsedDocument parse(
            UUID revisionId,
            String mediaType,
            String fileName,
            InputStream source,
            long declaredLength,
            DocumentParseLimits limits
    ) {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (declaredLength < -1) {
            throw new IllegalArgumentException("declaredLength must be -1 or non-negative");
        }
        if (declaredLength > limits.maximumSourceBytes()) {
            throw new DocumentParseException("source exceeds maximumSourceBytes");
        }
        String normalizedMediaType = normalizeMediaType(mediaType);
        select(normalizedMediaType, fileName);
        byte[] bytes = readBounded(source, limits.maximumSourceBytes());
        if (declaredLength >= 0 && declaredLength != bytes.length) {
            throw new DocumentParseException("source length does not match declaredLength");
        }
        return parse(revisionId, normalizedMediaType, fileName, bytes, limits);
    }

    /** Parses source bytes that were already bounded and checksummed by the upload layer. */
    public ParsedDocument parse(
            UUID revisionId,
            String mediaType,
            String fileName,
            byte[] sourceBytes,
            DocumentParseLimits limits
    ) {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(sourceBytes, "sourceBytes must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (sourceBytes.length > limits.maximumSourceBytes()) {
            throw new DocumentParseException("source exceeds maximumSourceBytes");
        }
        String normalizedMediaType = normalizeMediaType(mediaType);
        DocumentParser parser = select(normalizedMediaType, fileName);
        return parser.parse(new DocumentParseInput(
                revisionId,
                normalizedMediaType,
                fileName,
                sourceBytes,
                limits
        ));
    }

    /** Returns the selected parser contract before revision identity is calculated. */
    public String parserContract(String mediaType, String fileName) {
        String normalizedMediaType = normalizeMediaType(mediaType);
        DocumentParser parser = select(normalizedMediaType, fileName);
        return parser.id() + ":" + parser.version();
    }

    private DocumentParser select(String normalizedMediaType, String fileName) {
        DocumentParser parser = mediaTypeParsers.get(normalizedMediaType);
        if (parser == null) {
            parser = extensionParsers.get(extension(fileName));
        }
        if (parser == null) {
            throw new DocumentParseException(
                    "no document parser supports the supplied media type or extension"
            );
        }
        return parser;
    }

    private static byte[] readBounded(InputStream source, int maximumBytes) {
        try {
            byte[] bytes = source.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new DocumentParseException("source exceeds maximumSourceBytes");
            }
            return bytes;
        } catch (IOException failure) {
            throw new DocumentParseException("failed to read source document", failure);
        }
    }

    private static void putUnique(
            Map<String, DocumentParser> target,
            String key,
            DocumentParser parser,
            String kind
    ) {
        DocumentParser previous = target.putIfAbsent(key, parser);
        if (previous != null) {
            throw new IllegalArgumentException(
                    kind + " " + key + " is claimed by both " + previous.id() + " and " + parser.id()
            );
        }
    }

    private static String normalizeMediaType(String mediaType) {
        Objects.requireNonNull(mediaType, "mediaType must not be null");
        String normalized = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        return normalized;
    }

    private static String normalizeExtension(String extension) {
        Objects.requireNonNull(extension, "extension must not be null");
        String normalized = extension.strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(".") || normalized.length() < 2) {
            throw new IllegalArgumentException("parser extensions must include a leading dot");
        }
        return normalized;
    }

    private static String extension(String fileName) {
        int lastSlash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > lastSlash ? fileName.substring(lastDot).toLowerCase(Locale.ROOT) : "";
    }
}
