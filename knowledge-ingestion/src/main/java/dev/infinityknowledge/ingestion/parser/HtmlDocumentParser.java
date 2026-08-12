package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** HTML parser that retains headings, paragraphs, lists, tables, code and image captions. */
public final class HtmlDocumentParser implements DocumentParser {

    /** Current parser contract version. */
    public static final String VERSION = "html-structure-v1";

    @Override
    public String id() {
        return "html-structure";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of("text/html", "application/xhtml+xml");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of(".html", ".htm", ".xhtml");
    }

    @Override
    public ParsedDocument parse(DocumentParseInput input) {
        try {
            Document document = Jsoup.parse(input.openStream(), null, "");
            document.select("script,style,noscript,template,[hidden],[aria-hidden=true]").remove();
            ElementAccumulator elements = new ElementAccumulator(input.revisionId(), input.limits());
            if (!document.title().isBlank()) {
                elements.add(ElementType.TITLE, document.title(), Map.of());
            }
            Element body = document.body();
            if (body != null) {
                for (Element element : body.select(
                        "h1,h2,h3,h4,h5,h6,p,pre,table,ul,ol,img"
                )) {
                    append(elements, element);
                }
            }
            return new ParsedDocument(id(), VERSION, elements.elements(), Map.of());
        } catch (IOException failure) {
            throw new DocumentParseException("failed to parse HTML document", failure);
        }
    }

    private static void append(ElementAccumulator elements, Element element) {
        String tag = element.tagName();
        if (tag.matches("h[1-6]")) {
            elements.heading(
                    Integer.parseInt(tag.substring(1)),
                    element.text(),
                    Map.of("tag", tag)
            );
            return;
        }
        switch (tag) {
            case "p" -> {
                if (!hasAncestor(element, Set.of("li", "td", "th", "pre"))) {
                    elements.add(ElementType.PARAGRAPH, element.text(), Map.of("tag", tag));
                }
            }
            case "pre" -> elements.add(ElementType.CODE, element.wholeText(), Map.of("tag", tag));
            case "table" -> elements.add(ElementType.TABLE, tableText(element), Map.of("tag", tag));
            case "ul", "ol" -> {
                if (!hasAncestor(element, Set.of("ul", "ol"))) {
                    elements.add(ElementType.LIST, listText(element), Map.of("tag", tag));
                }
            }
            case "img" -> {
                String caption = element.hasAttr("alt") ? element.attr("alt") : element.attr("title");
                elements.add(ElementType.IMAGE, caption, Map.of("tag", tag));
            }
            default -> throw new IllegalStateException("unhandled selected HTML tag " + tag);
        }
    }

    private static boolean hasAncestor(Element element, Set<String> tags) {
        return element.parents().stream().anyMatch(parent -> tags.contains(parent.tagName()));
    }

    private static String tableText(Element table) {
        List<String> rows = new ArrayList<>();
        for (Element row : table.select("tr")) {
            List<String> cells = row.select("th,td").eachText();
            if (!cells.isEmpty()) {
                rows.add(String.join("\t", cells));
            }
        }
        return String.join("\n", rows);
    }

    private static String listText(Element list) {
        List<String> items = new ArrayList<>();
        for (Element child : list.children()) {
            if ("li".equals(child.tagName())) {
                Element copy = child.clone();
                copy.select("ul,ol").remove();
                if (!copy.text().isBlank()) {
                    items.add("- " + copy.text());
                }
            }
        }
        return String.join("\n", items);
    }
}
