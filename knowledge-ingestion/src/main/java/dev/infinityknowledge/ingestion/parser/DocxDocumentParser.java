package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DOCX parser with archive-bomb preflight and document-order structure extraction. */
public final class DocxDocumentParser implements DocumentParser {

    /** Current parser contract version. */
    public static final String VERSION = "poi-docx-structure-v1";

    private static final Pattern HEADING_STYLE = Pattern.compile(
            "(?:heading|标题)\\s*([1-6])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    @Override
    public String id() {
        return "poi-docx-structure";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of(".docx");
    }

    @Override
    public ParsedDocument parse(DocumentParseInput input) {
        byte[] source = input.sourceBytes();
        OfficeArchiveGuard.verify(source, input.limits());
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(source))) {
            ElementAccumulator elements = new ElementAccumulator(input.revisionId(), input.limits());
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement.getElementType() == BodyElementType.PARAGRAPH) {
                    appendParagraph(elements, (XWPFParagraph) bodyElement);
                } else if (bodyElement.getElementType() == BodyElementType.TABLE) {
                    elements.add(
                            ElementType.TABLE,
                            tableText((XWPFTable) bodyElement),
                            Map.of()
                    );
                }
            }
            String title = document.getProperties().getCoreProperties().getTitle();
            Map<String, String> attributes = title == null || title.isBlank()
                    ? Map.of()
                    : Map.of("title", title);
            return new ParsedDocument(id(), VERSION, elements.elements(), attributes);
        } catch (DocumentParseException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new DocumentParseException("failed to parse DOCX document", failure);
        }
    }

    private static void appendParagraph(ElementAccumulator elements, XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        int heading = headingLevel(paragraph.getStyle());
        if (heading > 0) {
            elements.heading(heading, text, Map.of("style", safeStyle(paragraph.getStyle())));
            return;
        }
        ElementType type = paragraph.getNumID() == null
                ? ElementType.PARAGRAPH
                : ElementType.LIST;
        elements.add(type, text, Map.of());
    }

    private static int headingLevel(String style) {
        if (style == null || style.isBlank()) {
            return 0;
        }
        Matcher matcher = HEADING_STYLE.matcher(style.toLowerCase(Locale.ROOT));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String safeStyle(String style) {
        return style == null || style.isBlank() ? "heading" : style;
    }

    private static String tableText(XWPFTable table) {
        List<String> rows = new ArrayList<>();
        table.getRows().forEach(row -> {
            List<String> cells = row.getTableCells().stream()
                    .map(XWPFTableCell::getText)
                    .map(String::strip)
                    .toList();
            rows.add(String.join("\t", cells));
        });
        return String.join("\n", rows);
    }
}
