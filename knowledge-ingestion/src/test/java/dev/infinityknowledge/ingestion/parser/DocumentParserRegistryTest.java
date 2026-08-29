package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserRegistryTest {

    @Test
    void selectsOneParserForContractAndParseWhenFormatHasMultipleImplementations() {
        DocumentParser baseline = new TestDocumentParser("baseline-parser", "v1");
        DocumentParser semantic = new TestDocumentParser("semantic-parser", "v2");
        DocumentParserRegistry registry = new DocumentParserRegistry(
                List.of(baseline, semantic),
                Map.of("text/plain", "baseline-parser")
        );

        var selection = registry.select(
                "text/plain; charset=UTF-8",
                "runbook.txt",
                Map.of("text/plain", "semantic-parser")
        );
        ParsedDocument parsed = selection.parse(
                UUID.randomUUID(),
                "runbook.txt",
                "故障处理".getBytes(StandardCharsets.UTF_8),
                DocumentParseLimits.defaults()
        );

        assertThat(selection.contract()).isEqualTo(
                "semantic-parser:v2:outputs=STANDARD_ELEMENTS"
        );
        assertThat(parsed.parserId()).isEqualTo("semantic-parser");
        assertThat(registry.resolveCanonicalMediaType("text/plain", "runbook.txt"))
                .isEqualTo("text/plain");
        assertThat(registry.capabilities())
                .extracting(ParserCapability::parserId)
                .containsExactly("baseline-parser", "semantic-parser");
        assertThat(registry.capabilities())
                .filteredOn(ParserCapability::defaultSelection)
                .extracting(ParserCapability::parserId)
                .containsExactly("baseline-parser");
        assertThat(registry.capabilities())
                .allSatisfy(capability -> assertThat(capability.outputCapabilities())
                        .containsExactly(ParserOutputCapability.STANDARD_ELEMENTS));
    }

    @Test
    void rejectsDuplicateParserIdsEvenWhenImplementationsClaimDifferentFormats() {
        DocumentParser first = new TestDocumentParser("duplicate", "v1");
        DocumentParser second = new TestDocumentParser("duplicate", "v2");

        assertThatThrownBy(() -> new DocumentParserRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate parser id");
    }

    @Test
    void explicitSelectionContractIsStableWhenParserRegistrationOrderChanges() {
        DocumentParserRegistry first = new DocumentParserRegistry(List.of(
                new MarkdownDocumentParser(),
                new PlainTextDocumentParser(),
                new HtmlDocumentParser(),
                new PdfDocumentParser(),
                new DocxDocumentParser()
        ));
        DocumentParserRegistry reordered = new DocumentParserRegistry(List.of(
                new DocxDocumentParser(),
                new PdfDocumentParser(),
                new HtmlDocumentParser(),
                new PlainTextDocumentParser(),
                new MarkdownDocumentParser()
        ));

        String firstContract = first.selectedParsersContract(
                first.defaultParserSelections()
        );
        String reorderedContract = reordered.selectedParsersContract(
                reordered.defaultParserSelections()
        );
        assertThat(firstContract).isEqualTo(reorderedContract);
        assertThat(firstContract).contains(
                "markdown-structure",
                "pdfbox-page",
                "poi-docx-structure",
                "outputs=FLAT_TABLE_TEXT,HIERARCHY,STANDARD_ELEMENTS",
                "outputs=PAGE_NUMBER,STANDARD_ELEMENTS"
        );
    }

    @Test
    void exposesOnlyCapabilitiesActuallyProvidedByBuiltInAdapters() {
        Map<String, List<ParserOutputCapability>> capabilities =
                DocumentParserRegistry.standard().capabilities().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                ParserCapability::parserId,
                                ParserCapability::outputCapabilities
                        ));

        assertThat(capabilities.get("plain-text"))
                .containsExactly(ParserOutputCapability.STANDARD_ELEMENTS);
        assertThat(capabilities.get("pdfbox-page")).containsExactly(
                ParserOutputCapability.PAGE_NUMBER,
                ParserOutputCapability.STANDARD_ELEMENTS
        );
        assertThat(List.of(
                "markdown-structure",
                "html-structure",
                "poi-docx-structure"
        )).allSatisfy(parserId -> assertThat(capabilities.get(parserId)).containsExactly(
                ParserOutputCapability.FLAT_TABLE_TEXT,
                ParserOutputCapability.HIERARCHY,
                ParserOutputCapability.STANDARD_ELEMENTS
        ));
        assertThat(capabilities.values()).allSatisfy(declared -> assertThat(declared)
                .doesNotContain(
                        ParserOutputCapability.BOUNDING_BOX,
                        ParserOutputCapability.NATIVE_ARTIFACT,
                        ParserOutputCapability.TABLE_STRUCTURE
                ));
    }

    @Test
    void selectedContractChangesWhenAdapterOutputGuaranteesChange() {
        DocumentParser standard = new TestDocumentParser("parser", "v1");
        DocumentParser hierarchical = new TestDocumentParser(
                "parser",
                "v1",
                Set.of(
                        ParserOutputCapability.STANDARD_ELEMENTS,
                        ParserOutputCapability.HIERARCHY
                )
        );

        String standardContract = new DocumentParserRegistry(List.of(standard))
                .selectedParsersContract(Map.of("text/plain", "parser"));
        String hierarchicalContract = new DocumentParserRegistry(List.of(hierarchical))
                .selectedParsersContract(Map.of("text/plain", "parser"));

        assertThat(hierarchicalContract).isNotEqualTo(standardContract);
        assertThat(hierarchicalContract).contains("outputs=HIERARCHY,STANDARD_ELEMENTS");
    }

    @Test
    void rejectsAdapterThatDoesNotProvideStandardElements() {
        DocumentParser nativeOnly = new TestDocumentParser(
                "native-only",
                "v1",
                Set.of(ParserOutputCapability.NATIVE_ARTIFACT)
        );

        assertThatThrownBy(() -> new DocumentParserRegistry(List.of(nativeOnly)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STANDARD_ELEMENTS");
    }

    @Test
    void selectedParserContractIgnoresInstalledButUnselectedAdapter() {
        DocumentParser baseline = new TestDocumentParser("baseline-parser", "v1");
        DocumentParser alternative = new TestDocumentParser("alternative-parser", "v2");
        DocumentParserRegistry baselineOnly = new DocumentParserRegistry(List.of(baseline));
        DocumentParserRegistry withAlternative = new DocumentParserRegistry(
                List.of(baseline, alternative),
                Map.of("text/plain", "baseline-parser")
        );

        String existingContract = baselineOnly.selectedParsersContract(
                Map.of("text/plain", "baseline-parser")
        );
        String unchangedContract = withAlternative.selectedParsersContract(
                Map.of("text/plain", "baseline-parser")
        );
        String alternativeContract = withAlternative.selectedParsersContract(
                Map.of("text/plain", "alternative-parser")
        );

        assertThat(unchangedContract).isEqualTo(existingContract);
        assertThat(alternativeContract).isNotEqualTo(existingContract);
    }

    @Test
    void selectedContractUsesOnlyTheImmutableSnapshotWhenDeploymentAddsAFormat() {
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(
                new PlainTextDocumentParser(),
                new MarkdownDocumentParser()
        ));

        String frozenContract = registry.selectedParsersContract(
                Map.of("text/plain", "plain-text")
        );

        assertThat(frozenContract)
                .contains("text/plain", "plain-text")
                .doesNotContain("text/markdown", "markdown-structure");
    }

    @Test
    void selectedContractRejectsUnknownCanonicalFormat() {
        DocumentParserRegistry registry = new DocumentParserRegistry(
                List.of(new PlainTextDocumentParser())
        );

        assertThatThrownBy(() -> registry.selectedParsersContract(
                Map.of("application/unknown", "plain-text")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown canonical media type");
    }

    @Test
    void selectedContractDoesNotSynthesizeADeploymentDefaultForEmptySnapshot() {
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(
                new TestDocumentParser("baseline-parser", "v1"),
                new TestDocumentParser("alternative-parser", "v2")
        ));

        assertThat(registry.selectedParsersContract(Map.of())).isEmpty();
    }

    @Test
    void selectedContractRejectsParserFromAnotherCanonicalFormat() {
        DocumentParserRegistry registry = DocumentParserRegistry.standard();
        Map<String, String> selections = new java.util.HashMap<>(
                registry.defaultParserSelections()
        );
        selections.put("text/plain", "markdown-structure");

        assertThatThrownBy(() -> registry.selectedParsersContract(
                selections
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match canonical media type");
    }

    @Test
    void configuredSelectionNeverFallsBackToDeploymentDefault() {
        DocumentParserRegistry registry = DocumentParserRegistry.standard();

        assertThatThrownBy(() -> registry.select(
                "text/plain",
                "runbook.txt",
                Map.of("text/markdown", "markdown-structure")
        )).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("omit the document canonical media type");
    }

    @Test
    void parsesHtmlStructureWithoutExecutableContent() {
        byte[] html = """
                <html><head><title>Runbook</title><script>secret()</script></head>
                <body><h1>Gateway</h1><p>Check health.</p>
                <table><tr><th>Code</th><th>Action</th></tr><tr><td>502</td><td>Retry</td></tr></table>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);

        ParsedDocument parsed = defaultSelection(
                "text/html; charset=UTF-8",
                "runbook.html"
        ).parse(
                UUID.randomUUID(),
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

        ParsedDocument parsed = defaultSelection(
                "application/octet-stream",
                "guide.docx"
        ).parse(
                UUID.randomUUID(),
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

        assertThatThrownBy(() -> defaultSelection(
                "application/pdf",
                "large.pdf"
        ).parse(
                UUID.randomUUID(),
                "large.pdf",
                new ByteArrayInputStream(pdf),
                pdf.length,
                limits
        )).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("maximumPages");
    }

    @Test
    void pdf_page_number_should_be_materialized_as_typed_provenance() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Page provenance");
                content.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }

        ParsedDocument parsed = defaultSelection(
                "application/pdf",
                "page.pdf"
        ).parse(
                UUID.randomUUID(),
                "page.pdf",
                new ByteArrayInputStream(pdf),
                pdf.length,
                DocumentParseLimits.defaults()
        );

        KnowledgeElement paragraph = parsed.elements().getFirst();
        assertThat(parsed.requireProvenance(paragraph.id()).pageNumber()).isEqualTo(1);
        assertThat(paragraph.attributes())
                .containsEntry(
                        dev.infinityknowledge.domain.document.ElementProvenance.PAGE_NUMBER_ATTRIBUTE,
                        "1"
                )
                .doesNotContainKey("page");
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

        assertThatThrownBy(() -> defaultSelection(
                "text/plain",
                "source.txt"
        ).parse(
                UUID.randomUUID(),
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
        assertThatThrownBy(() -> defaultSelection(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "bomb.docx"
        ).parse(
                UUID.randomUUID(),
                "bomb.docx",
                new ByteArrayInputStream(archive),
                archive.length,
                limits
        )).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("maximumExpandedBytes");
    }

    /** 测试夹具显式物化部署默认值，避免走不存在的无配置执行入口。 */
    private static ParserSelection defaultSelection(String mediaType, String fileName) {
        DocumentParserRegistry registry = DocumentParserRegistry.standard();
        return registry.select(
                mediaType,
                fileName,
                registry.defaultParserSelections()
        );
    }

    /** 只用于验证注册表选择语义的最小 Parser。 */
    private record TestDocumentParser(
            String id,
            String version,
            Set<ParserOutputCapability> outputCapabilities
    ) implements DocumentParser {

        private TestDocumentParser(String id, String version) {
            this(id, version, Set.of(ParserOutputCapability.STANDARD_ELEMENTS));
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
            return Set.of(".txt");
        }

        @Override
        public ParsedDocument parse(DocumentParseInput input) {
            return new ParsedDocument(
                    id,
                    version,
                    List.of(new KnowledgeElement(
                            UUID.randomUUID(),
                            input.revisionId(),
                            null,
                            ElementType.PARAGRAPH,
                            0,
                            List.of(),
                            new String(input.sourceBytes(), StandardCharsets.UTF_8),
                            Map.of()
                    )),
                    Map.of()
            );
        }
    }
}
