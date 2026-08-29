package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;

import dev.infinityknowledge.ingestion.parser.NormalizedTextSource.Line;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 严格使用 UTF-8，并保留空行段落边界的纯文本 Parser。 */
public final class PlainTextDocumentParser implements DocumentParser {

    /** 当前 Parser 契约版本。 */
    public static final String VERSION = "plain-text-v2";

    @Override
    public String id() {
        return "plain-text";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String canonicalMediaType() {
        return "text/plain";
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
        NormalizedTextSource source = NormalizedTextSource.decode(
                input.sourceBytes(),
                "plain text"
        );
        List<Line> lines = source.lines();
        ElementAccumulator elements = new ElementAccumulator(
                input.revisionId(),
                input.limits(),
                source.artifact()
        );
        int index = 0;
        while (index < lines.size()) {
            while (index < lines.size() && lines.get(index).text().isBlank()) {
                index++;
            }
            if (index >= lines.size()) {
                break;
            }
            int start = lines.get(index).startOffset();
            int end = lines.get(index).endOffset();
            index++;
            while (index < lines.size() && !lines.get(index).text().isBlank()) {
                end = lines.get(index).endOffset();
                index++;
            }
            var range = source.strip(start, end);
            if (!range.isEmpty()) {
                elements.addFromArtifact(
                        ElementType.PARAGRAPH,
                        range.startOffset(),
                        range.endOffset(),
                        Map.of()
                );
            }
        }
        return elements.parsedDocument(
                id(),
                VERSION,
                Map.of("charset", "UTF-8", "artifactContract", NormalizedTextSource.CONTRACT)
        );
    }
}
