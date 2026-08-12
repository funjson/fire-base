package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Strict UTF-8 plain-text parser that preserves blank-line paragraph boundaries. */
public final class PlainTextDocumentParser implements DocumentParser {

    /** Current parser contract version. */
    public static final String VERSION = "plain-text-v1";

    @Override
    public String id() {
        return "plain-text";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of("text/plain");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of(".txt", ".text", ".log");
    }

    @Override
    public ParsedDocument parse(DocumentParseInput input) {
        String text = decodeUtf8(input.sourceBytes());
        ElementAccumulator elements = new ElementAccumulator(input.revisionId(), input.limits());
        for (String paragraph : text.split("(?:\\R\\s*){2,}")) {
            elements.add(ElementType.PARAGRAPH, paragraph, Map.of());
        }
        return new ParsedDocument(id(), VERSION, elements.elements(), Map.of("charset", "UTF-8"));
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new DocumentParseException("plain text must be valid UTF-8", failure);
        }
    }
}
