package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementProvenance;
import dev.infinityknowledge.domain.document.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** 受资源预算约束，并为每个元素保留页级来源信息的 PDF Parser。 */
public final class PdfDocumentParser implements DocumentParser {

    /** 当前 Parser 契约版本。 */
    public static final String VERSION = "pdfbox-page-v2";

    @Override
    public String id() {
        return "pdfbox-page";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String canonicalMediaType() {
        return "application/pdf";
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of("application/pdf");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of(".pdf");
    }

    /** PDFBox 按页提取正文，并在每个页面元素上保留一基页码。 */
    @Override
    public Set<ParserOutputCapability> outputCapabilities() {
        return Set.of(
                ParserOutputCapability.STANDARD_ELEMENTS,
                ParserOutputCapability.PAGE_NUMBER
        );
    }

    @Override
    public ParsedDocument parse(DocumentParseInput input) {
        try (PDDocument document = Loader.loadPDF(input.sourceBytes())) {
            if (document.isEncrypted()) {
                throw new DocumentParseException("encrypted PDF documents are not supported");
            }
            int pages = document.getNumberOfPages();
            if (pages > input.limits().maximumPages()) {
                throw new DocumentParseException("PDF exceeds maximumPages");
            }
            ElementAccumulator elements = new ElementAccumulator(input.revisionId(), input.limits());
            String title = document.getDocumentInformation().getTitle();
            if (title != null && !title.isBlank()) {
                elements.add(ElementType.TITLE, title, Map.of());
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                elements.add(
                        ElementType.PARAGRAPH,
                        stripper.getText(document),
                        Map.of(
                                ElementProvenance.PAGE_NUMBER_ATTRIBUTE,
                                Integer.toString(page)
                        )
                );
            }
            return new ParsedDocument(
                    id(),
                    VERSION,
                    elements.elements(),
                    Map.of("pages", Integer.toString(pages))
            );
        } catch (DocumentParseException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DocumentParseException("failed to parse PDF document", failure);
        }
    }
}
