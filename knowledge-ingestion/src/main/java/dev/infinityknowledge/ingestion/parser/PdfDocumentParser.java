package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Bounded PDF text parser that retains page provenance for every emitted element. */
public final class PdfDocumentParser implements DocumentParser {

    /** Current parser contract version. */
    public static final String VERSION = "pdfbox-page-v1";

    @Override
    public String id() {
        return "pdfbox-page";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of("application/pdf");
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of(".pdf");
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
                        Map.of("page", Integer.toString(page))
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
