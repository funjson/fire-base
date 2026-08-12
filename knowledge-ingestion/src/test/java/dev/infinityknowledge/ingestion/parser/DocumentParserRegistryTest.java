package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserRegistryTest {

    @Test
    void parsesHtmlStructureWithoutExecutableContent() {
        byte[] html = """
                <html><head><title>Runbook</title><script>secret()</script></head>
                <body><h1>Gateway</h1><p>Check health.</p>
                <table><tr><th>Code</th><th>Action</th></tr><tr><td>502</td><td>Retry</td></tr></table>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);

        ParsedDocument parsed = DocumentParserRegistry.standard().parse(
                UUID.randomUUID(),
                "text/html; charset=UTF-8",
                "runbook.html",
                new ByteArrayInputStream(html),
                html.length,
                DocumentParseLimits.defaults()
        );

        assertThat(parsed.elements()).extracting(KnowledgeElement::type)
                .containsExactly(
                        ElementType.TITLE,
                        ElementType.HEADING,
                        ElementType.PARAGRAPH,
                        ElementType.TABLE
                );
        assertThat(parsed.elements()).noneMatch(element -> element.content().contains("secret"));
        assertThat(parsed.elements().get(2).sectionPath()).containsExactly("Gateway");
    }

    @Test
    void parsesDocxHeadingAndParagraph() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Authentication");
            document.createParagraph().createRun().setText("Check the gateway token.");
            document.write(output);
            docx = output.toByteArray();
        }

        ParsedDocument parsed = DocumentParserRegistry.standard().parse(
                UUID.randomUUID(),
                "application/octet-stream",
                "guide.docx",
                new ByteArrayInputStream(docx),
                docx.length,
                DocumentParseLimits.defaults()
        );

        assertThat(parsed.parserId()).isEqualTo("poi-docx-structure");
        assertThat(parsed.elements()).extracting(KnowledgeElement::type)
                .containsExactly(ElementType.HEADING, ElementType.PARAGRAPH);
        assertThat(parsed.elements().get(1).sectionPath()).containsExactly("Authentication");
    }

    @Test
    void rejectsPdfAbovePageBudgetBeforeTextExtraction() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }
        DocumentParseLimits limits = new DocumentParseLimits(
                1_000_000,
                2_000_000,
                1,
                100,
                10_000,
                100,
                100
        );

        assertThatThrownBy(() -> DocumentParserRegistry.standard().parse(
                UUID.randomUUID(),
                "application/pdf",
                "large.pdf",
                new ByteArrayInputStream(pdf),
                pdf.length,
                limits
        )).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("maximumPages");
    }

    @Test
    void rejectsSourceAboveByteBudgetWithoutParsing() {
        byte[] text = "12345".getBytes(StandardCharsets.UTF_8);
        DocumentParseLimits limits = new DocumentParseLimits(
                4,
                8,
                1,
                1,
                10,
                1,
                10
        );

        assertThatThrownBy(() -> DocumentParserRegistry.standard().parse(
                UUID.randomUUID(),
                "text/plain",
                "source.txt",
                new ByteArrayInputStream(text),
                -1,
                limits
        )).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("maximumSourceBytes");
    }

    @Test
    void rejectsOfficeArchiveAboveExpansionBudget() throws Exception {
        byte[] archive;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(new byte[100_000]);
            zip.closeEntry();
            zip.finish();
            archive = output.toByteArray();
        }
        DocumentParseLimits limits = new DocumentParseLimits(
                1_024,
                2_048,
                10,
                100,
                10_000,
                100,
                100
        );

        assertThat(archive.length).isLessThan(limits.maximumSourceBytes());
        assertThatThrownBy(() -> DocumentParserRegistry.standard().parse(
                UUID.randomUUID(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "bomb.docx",
                new ByteArrayInputStream(archive),
                archive.length,
                limits
        )).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("maximumExpandedBytes");
    }
}
