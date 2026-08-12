package dev.infinityknowledge.ingestion.parser;

import java.util.Set;

/** Pluggable structural parser selected by normalized media type or file extension. */
public interface DocumentParser {

    /** Stable implementation identifier used in ingestion diagnostics. */
    String id();

    /** Version of the parser contract included in immutable revision identity. */
    String version();

    /** Exact lower-case media types supported by this parser. */
    Set<String> supportedMediaTypes();

    /** Lower-case extensions, including the leading dot, supported as a fallback. */
    Set<String> supportedExtensions();

    /** Parses one already bounded source into ordered structural elements. */
    ParsedDocument parse(DocumentParseInput input);
}
