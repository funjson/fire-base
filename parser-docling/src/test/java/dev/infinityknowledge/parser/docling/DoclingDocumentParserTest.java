package dev.infinityknowledge.parser.docling;

import ai.docling.core.DoclingDocument;
import ai.docling.core.DoclingDocument.CodeItem;
import ai.docling.core.DoclingDocument.ContentLayer;
import ai.docling.core.DoclingDocument.DocItemLabel;
import ai.docling.core.DoclingDocument.GroupItem;
import ai.docling.core.DoclingDocument.GroupLabel;
import ai.docling.core.DoclingDocument.ListItem;
import ai.docling.core.DoclingDocument.PictureItem;
import ai.docling.core.DoclingDocument.ProvenanceItem;
import ai.docling.core.DoclingDocument.RefItem;
import ai.docling.core.DoclingDocument.SectionHeaderItem;
import ai.docling.core.DoclingDocument.TableCell;
import ai.docling.core.DoclingDocument.TableData;
import ai.docling.core.DoclingDocument.TableItem;
import ai.docling.core.DoclingDocument.TextItem;
import ai.docling.core.DoclingDocument.TitleItem;
import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ImageRefMode;
import ai.docling.serve.api.convert.request.options.OcrEngine;
import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.request.options.PdfBackend;
import ai.docling.serve.api.convert.request.options.ProcessingPipeline;
import ai.docling.serve.api.convert.request.options.TableFormerMode;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import ai.docling.serve.api.convert.response.DocumentResponse;
import ai.docling.serve.api.convert.response.ErrorItem;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParseInput;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.ingestion.parser.ParsedDocument;
import dev.infinityknowledge.ingestion.parser.ParserOutputCapability;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用内存 Fake DoclingServeApi 验证外部协议，不启动容器或真实模型。 */
class DoclingDocumentParserTest {

    private static final String SERVER_CONTRACT = "DoclingDocument@1.7.0";
    private static final String DEPLOYMENT_CONTRACT =
            "docling-serve-v1.20.0@sha256:0123abcd";

    @Test
    void mapsLosslessJsonByBodyReadingOrderAndPreservesHierarchy() {
        AtomicReference<ConvertDocumentRequest> captured = new AtomicReference<>();
        DoclingServeApi api = fakeApi(successResponse(fixture()), captured);
        DoclingDocumentParser parser = parser(api, DoclingDocumentParser.SourceFormat.PDF);
        UUID revisionId = UUID.randomUUID();

        ParsedDocument parsed = parser.parse(input(
                revisionId,
                "application/pdf",
                "handbook.pdf",
                DocumentParseLimits.defaults()
        ));

        assertThat(parsed.parserId()).isEqualTo("docling-serve-pdf");
        assertThat(parsed.parserVersion())
                .contains("docling-java-0.5.3")
                .contains("request-profile-v1")
                .contains(DEPLOYMENT_CONTRACT)
                .contains(SERVER_CONTRACT)
                .contains("reading-order-v2-docx-coverage-v1");
        assertThat(parsed.elements()).extracting(KnowledgeElement::type)
                .containsExactly(
                        ElementType.TITLE,
                        ElementType.HEADING,
                        ElementType.PARAGRAPH,
                        ElementType.LIST,
                        ElementType.TABLE,
                        ElementType.CODE
                );
        KnowledgeElement heading = parsed.elements().get(1);
        KnowledgeElement paragraph = parsed.elements().get(2);
        assertThat(heading.sectionPath()).containsExactly("Overview");
        assertThat(paragraph.sectionPath()).containsExactly("Overview");
        assertThat(paragraph.parentId()).isEqualTo(heading.id());
        assertThat(paragraph.attributes())
                .containsEntry("pageNumber", "2")
                .containsEntry("doclingSelfRef", "#/texts/0")
                .containsEntry("doclingParentRef", "#/body");
        assertThat(parsed.requireProvenance(paragraph.id()).pageNumber()).isEqualTo(2);
        assertThat(parsed.elements().get(4).content())
                .isEqualTo("Name\tOwner\nGateway\tPlatform");
        assertThat(parsed.attributes())
                .containsEntry("doclingSchemaName", "DoclingDocument")
                .containsEntry("doclingSchemaVersion", "1.7.0");

        ConvertDocumentRequest request = captured.get();
        assertThat(request).isNotNull();
        assertThat(request.getSources()).hasSize(1);
        assertThat(request.getOptions().getToFormats()).containsExactly(OutputFormat.JSON);
        assertThat(request.getOptions().getImageExportMode()).isEqualTo(ImageRefMode.PLACEHOLDER);
        assertThat(request.getOptions().getDoOcr()).isTrue();
        assertThat(request.getOptions().getForceOcr()).isFalse();
        assertThat(request.getOptions().getOcrEngine()).isEqualTo(OcrEngine.AUTO);
        assertThat(request.getOptions().getOcrLang()).isEmpty();
        assertThat(request.getOptions().getPdfBackend()).isEqualTo(PdfBackend.DLPARSE_V4);
        assertThat(request.getOptions().getTableMode()).isEqualTo(TableFormerMode.ACCURATE);
        assertThat(request.getOptions().getTableCellMatching()).isTrue();
        assertThat(request.getOptions().getPipeline()).isEqualTo(ProcessingPipeline.STANDARD);
        assertThat(request.getOptions().getPageRange()).containsExactly(1, 500);
        assertThat(request.getOptions().getAbortOnError()).isTrue();
        assertThat(request.getOptions().getDoTableStructure()).isTrue();
        assertThat(request.getOptions().getIncludeImages()).isFalse();
        assertThat(request.getOptions().getImagesScale()).isEqualTo(2.0);
        assertThat(request.getOptions().getDoCodeEnrichment()).isFalse();
        assertThat(request.getOptions().getDoFormulaEnrichment()).isFalse();
        assertThat(request.getOptions().getDoPictureClassification()).isFalse();
        assertThat(request.getOptions().getDoPictureDescription()).isFalse();
        assertThat(request.getOptions().getDocumentTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void exposesOnlyCapabilitiesTheCurrentDomainCanPersist() {
        DoclingServeApi api = fakeApi(successResponse(fixture()), new AtomicReference<>());
        DoclingDocumentParser pdf = parser(api, DoclingDocumentParser.SourceFormat.PDF);
        DoclingDocumentParser docx = parser(api, DoclingDocumentParser.SourceFormat.DOCX);

        assertThat(pdf.outputCapabilities()).containsExactlyInAnyOrder(
                ParserOutputCapability.STANDARD_ELEMENTS,
                ParserOutputCapability.HIERARCHY,
                ParserOutputCapability.PAGE_NUMBER,
                ParserOutputCapability.FLAT_TABLE_TEXT
        );
        assertThat(docx.outputCapabilities()).containsExactlyInAnyOrder(
                ParserOutputCapability.STANDARD_ELEMENTS,
                ParserOutputCapability.HIERARCHY,
                ParserOutputCapability.FLAT_TABLE_TEXT
        );
        Set<ParserOutputCapability> unimplemented = Set.of(
                ParserOutputCapability.BOUNDING_BOX,
                ParserOutputCapability.TABLE_STRUCTURE,
                ParserOutputCapability.NATIVE_ARTIFACT
        );
        assertThat(pdf.outputCapabilities()).doesNotContainAnyElementsOf(unimplemented);
        assertThat(docx.outputCapabilities()).doesNotContainAnyElementsOf(unimplemented);
    }

    @Test
    void rejectsPartialResponsesWithoutFallingBack() {
        InBodyConvertDocumentResponse response = InBodyConvertDocumentResponse.builder()
                .status("partial_success")
                .document(DocumentResponse.builder()
                        .filename("handbook.pdf")
                        .jsonContent(fixture())
                        .build())
                .build();
        DoclingDocumentParser parser = parser(
                fakeApi(response, new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.PDF
        );

        assertThatThrownBy(() -> parser.parse(input(
                UUID.randomUUID(),
                "application/pdf",
                "handbook.pdf",
                DocumentParseLimits.defaults()
        ))).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("DOCLING_PARTIAL_RESPONSE");
    }

    @Test
    void rejectsSuccessResponsesThatStillContainErrors() {
        InBodyConvertDocumentResponse response = InBodyConvertDocumentResponse.builder()
                .status("success")
                .error(ErrorItem.builder()
                        .componentType("document_backend")
                        .moduleName("docling")
                        .errorMessage("redacted in application logs")
                        .build())
                .document(DocumentResponse.builder()
                        .filename("handbook.pdf")
                        .jsonContent(fixture())
                        .build())
                .build();
        DoclingDocumentParser parser = parser(
                fakeApi(response, new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.PDF
        );

        assertThatThrownBy(() -> parser.parse(input(
                UUID.randomUUID(),
                "application/pdf",
                "handbook.pdf",
                DocumentParseLimits.defaults()
        ))).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("DOCLING_RESPONSE_ERRORS")
                .hasMessageNotContaining("redacted");
    }

    @Test
    void rejectsSchemaDriftAndMalformedReadingOrderReferences() {
        DoclingDocument wrongSchema = fixture().toBuilder().version("1.8.0").build();
        DoclingDocument badReference = fixture().toBuilder()
                .body(body(List.of(ref("#/texts/99"))))
                .build();

        assertParseFailure(wrongSchema, "DOCLING_SCHEMA_MISMATCH");
        assertParseFailure(badReference, "DOCLING_RESPONSE_INVALID");
    }

    @Test
    void doesNotEchoMalformedReferencesInFailures() {
        String confidentialReference = "bearer-secret-document-content";
        DoclingDocument document = fixture().toBuilder()
                .body(body(List.of(ref(confidentialReference))))
                .build();

        assertThatThrownBy(() -> parser(
                fakeApi(successResponse(document), new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.PDF
        ).parse(input(
                UUID.randomUUID(),
                "application/pdf",
                "handbook.pdf",
                DocumentParseLimits.defaults()
        ))).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("unsupported or malformed reference")
                .hasMessageNotContaining(confidentialReference);
    }

    @Test
    void rejectsUnsupportedBodyNodesInsteadOfDroppingThem() {
        PictureItem picture = PictureItem.builder()
                .selfRef("#/pictures/0")
                .parent(ref("#/body"))
                .contentLayer(ContentLayer.BODY)
                .label("picture")
                .build();
        DoclingDocument document = fixture().toBuilder()
                .body(body(List.of(ref("#/pictures/0"))))
                .pictures(List.of(picture))
                .build();

        assertParseFailure(document, "body contained an unsupported non-text item");
    }

    @Test
    void rejectsForwardIndexableParentInsteadOfFallingBackToCurrentHeading() {
        DoclingDocument document = fixture().toBuilder()
                .body(body(List.of(ref("#/texts/0"), ref("#/texts/2"))))
                .clearTexts()
                .texts(List.of(
                        paragraphWithParent(
                                "#/texts/0",
                                "child before parent",
                                "#/texts/2",
                                1
                        ),
                        paragraph("#/texts/1", "unused", 1),
                        heading("#/texts/2", "Future heading", 1, 1)
                ))
                .build();

        assertParseFailure(
                document,
                "indexable parent had not been emitted before its child"
        );
    }

    @Test
    void traversesV110NestedItemChildrenInReadingOrder() {
        TitleItem title = title("#/texts/0", "Runbook", 1).toBuilder()
                .children(List.of(ref("#/texts/1")))
                .build();
        SectionHeaderItem section = heading("#/texts/1", "Operations", 1, 1)
                .toBuilder()
                .parent(ref("#/texts/0"))
                .children(List.of(ref("#/texts/2"), ref("#/texts/3")))
                .build();
        DoclingDocument document = fixture().toBuilder()
                .body(body(List.of(ref("#/texts/0"))))
                .clearTexts()
                .texts(List.of(
                        title,
                        section,
                        paragraphWithParent(
                                "#/texts/2",
                                "first nested paragraph",
                                "#/texts/1",
                                1
                        ),
                        paragraphWithParent(
                                "#/texts/3",
                                "second nested paragraph",
                                "#/texts/1",
                                1
                        )
                ))
                .clearTables()
                .build();
        DoclingDocumentParser parser = parser(
                fakeApi(successResponse(document), new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.PDF
        );

        ParsedDocument parsed = parser.parse(input(
                UUID.randomUUID(),
                "application/pdf",
                "handbook.pdf",
                DocumentParseLimits.defaults()
        ));

        assertThat(parsed.elements())
                .extracting(KnowledgeElement::content)
                .containsExactly(
                        "Runbook",
                        "Operations",
                        "first nested paragraph",
                        "second nested paragraph"
                );
    }

    @Test
    void rejectsUnreachableBodyTableInsteadOfReturningTruncatedSuccess() {
        DoclingDocument document = fixture().toBuilder()
                .body(body(List.of(ref("#/texts/0"))))
                .clearTexts()
                .texts(List.of(paragraph("#/texts/0", "visible paragraph", 1)))
                .clearTables()
                .tables(List.of(table(1)))
                .build();

        assertParseFailure(
                document,
                "BODY table item was unreachable from reading order"
        );
    }

    @Test
    void allowsGroupContainerParentWithoutInventingAnElementParent() {
        GroupItem container = GroupItem.builder()
                .selfRef("#/groups/0")
                .children(List.of(ref("#/texts/0")))
                .contentLayer(ContentLayer.BODY)
                .label(GroupLabel.UNSPECIFIED)
                .build();
        DoclingDocument document = fixture().toBuilder()
                .body(body(List.of(ref("#/groups/0"))))
                .clearTexts()
                .texts(List.of(paragraphWithParent(
                        "#/texts/0",
                        "inside a structural container",
                        "#/groups/0",
                        1
                )))
                .groups(List.of(container))
                .clearTables()
                .build();
        DoclingDocumentParser parser = parser(
                fakeApi(successResponse(document), new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.PDF
        );

        ParsedDocument parsed = parser.parse(input(
                UUID.randomUUID(),
                "application/pdf",
                "handbook.pdf",
                DocumentParseLimits.defaults()
        ));

        assertThat(parsed.elements()).hasSize(1);
        assertThat(parsed.elements().getFirst().parentId()).isNull();
    }

    @Test
    void wrapsTransportFailureWithStableErrorCodeWithoutResponseContent() {
        DoclingServeApi api = fakeApiFailure(new IllegalStateException("raw response content"));
        DoclingDocumentParser parser = parser(api, DoclingDocumentParser.SourceFormat.PDF);

        assertThatThrownBy(() -> parser.parse(input(
                UUID.randomUUID(),
                "application/pdf",
                "handbook.pdf",
                DocumentParseLimits.defaults()
        ))).isInstanceOf(DocumentParseException.class)
                .hasMessage("DOCLING_TRANSPORT_FAILED: Docling Serve request failed")
                .hasMessageNotContaining("raw response content")
                .satisfies(failure -> assertThat(failure.getCause()).isNull());
    }

    @Test
    void rejectsUnsafeDocxBeforeCallingVendor() {
        AtomicReference<ConvertDocumentRequest> captured = new AtomicReference<>();
        DoclingDocumentParser parser = parser(
                fakeApi(successResponse(fixture()), captured),
                DoclingDocumentParser.SourceFormat.DOCX
        );

        assertThatThrownBy(() -> parser.parse(new DocumentParseInput(
                UUID.randomUUID(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "unsafe.docx",
                "not an archive".getBytes(StandardCharsets.UTF_8),
                DocumentParseLimits.defaults()
        ))).isInstanceOf(DocumentParseException.class)
                .hasMessage("Office source is not a ZIP archive");
        assertThat(captured).hasNullValue();
    }

    @Test
    void rejectsConversionImmediatelyWhenBulkheadIsFull() {
        AtomicReference<ConvertDocumentRequest> captured = new AtomicReference<>();
        Semaphore bulkhead = new Semaphore(1, true);
        bulkhead.acquireUninterruptibly();
        DoclingDocumentParser parser = new DoclingDocumentParser(
                fakeApi(successResponse(fixture()), captured),
                DoclingDocumentParser.SourceFormat.PDF,
                DEPLOYMENT_CONTRACT,
                DoclingServerContract.parse(SERVER_CONTRACT),
                Duration.ofSeconds(30),
                bulkhead
        );
        try {
            assertThatThrownBy(() -> parser.parse(input(
                    UUID.randomUUID(),
                    "application/pdf",
                    "handbook.pdf",
                    DocumentParseLimits.defaults()
            ))).isInstanceOf(DocumentParseException.class)
                    .hasMessageContaining("DOCLING_BUSY");
            assertThat(captured).hasNullValue();
        } finally {
            bulkhead.release();
        }
    }

    @Test
    void docxDoesNotInventPageNumbersWhenProvenanceIsUnavailable() {
        DoclingDocument noPages = fixture().toBuilder()
                .clearTexts()
                .texts(fixture().getTexts().stream()
                        .map(DoclingDocumentParserTest::withoutProvenance)
                        .toList())
                .clearTables()
                .tables(List.of(table(null)))
                .build();
        DoclingDocumentParser parser = parser(
                fakeApi(successResponse(noPages), new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.DOCX
        );

        ParsedDocument parsed = parser.parse(input(
                UUID.randomUUID(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "handbook.docx",
                DocumentParseLimits.defaults()
        ));

        assertThat(parsed.elements()).allSatisfy(element ->
                assertThat(element.attributes()).doesNotContainKey("pageNumber")
        );
    }

    @Test
    void rejectsDocxWhenSourceTableIsMissingFromLosslessResponse() {
        DoclingDocument withoutTable = fixture().toBuilder()
                .body(body(List.of(
                        ref("#/texts/1"),
                        ref("#/texts/2"),
                        ref("#/texts/0"),
                        ref("#/texts/3"),
                        ref("#/texts/4")
                )))
                .clearTables()
                .build();
        DoclingDocumentParser parser = parser(
                fakeApi(successResponse(withoutTable), new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.DOCX
        );

        assertThatThrownBy(() -> parser.parse(docxInput(docxWithTable())))
                .isInstanceOf(DocumentParseException.class)
                .hasMessage(
                        "DOCLING_SOURCE_COVERAGE_MISMATCH: "
                                + "DOCX table content was not preserved"
                )
                .hasMessageNotContaining("Gateway")
                .hasMessageNotContaining("Platform");
    }

    @Test
    void acceptsDocxWhenEverySourceTableHasAnIndependentTableElement() {
        DoclingDocumentParser parser = parser(
                fakeApi(successResponse(fixture()), new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.DOCX
        );

        ParsedDocument parsed = parser.parse(docxInput(docxWithTable()));

        assertThat(parsed.elements())
                .extracting(KnowledgeElement::type)
                .contains(ElementType.TABLE);
    }

    private static void assertParseFailure(DoclingDocument document, String errorCode) {
        DoclingDocumentParser parser = parser(
                fakeApi(successResponse(document), new AtomicReference<>()),
                DoclingDocumentParser.SourceFormat.PDF
        );
        assertThatThrownBy(() -> parser.parse(input(
                UUID.randomUUID(),
                "application/pdf",
                "handbook.pdf",
                DocumentParseLimits.defaults()
        ))).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining(errorCode);
    }

    private static DoclingDocumentParser parser(
            DoclingServeApi api,
            DoclingDocumentParser.SourceFormat format
    ) {
        return new DoclingDocumentParser(
                api,
                format,
                DEPLOYMENT_CONTRACT,
                DoclingServerContract.parse(SERVER_CONTRACT),
                Duration.ofSeconds(30),
                new Semaphore(2, true)
        );
    }

    private static DocumentParseInput input(
            UUID revisionId,
            String mediaType,
            String fileName,
            DocumentParseLimits limits
    ) {
        byte[] source = mediaType.contains("wordprocessingml")
                ? emptyDocx()
                : "fake source".getBytes(StandardCharsets.UTF_8);
        return new DocumentParseInput(
                revisionId,
                mediaType,
                fileName,
                source,
                limits
        );
    }

    private static DocumentParseInput docxInput(byte[] source) {
        return new DocumentParseInput(
                UUID.randomUUID(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "handbook.docx",
                source,
                DocumentParseLimits.defaults()
        );
    }

    private static byte[] emptyDocx() {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.write(bytes);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("memory DOCX creation failed", impossible);
        }
    }

    private static byte[] docxWithTable() {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Name");
            table.getRow(0).getCell(1).setText("Owner");
            table.getRow(1).getCell(0).setText("Gateway");
            table.getRow(1).getCell(1).setText("Platform");
            document.write(bytes);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("memory DOCX creation failed", impossible);
        }
    }

    private static InBodyConvertDocumentResponse successResponse(DoclingDocument document) {
        return InBodyConvertDocumentResponse.builder()
                .status("success")
                .document(DocumentResponse.builder()
                        .filename("handbook.pdf")
                        .jsonContent(document)
                        .build())
                .build();
    }

    private static DoclingServeApi fakeApi(
            ConvertDocumentResponse response,
            AtomicReference<ConvertDocumentRequest> captured
    ) {
        return (DoclingServeApi) Proxy.newProxyInstance(
                DoclingServeApi.class.getClassLoader(),
                new Class<?>[]{DoclingServeApi.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "convertSource" -> {
                        captured.set((ConvertDocumentRequest) arguments[0]);
                        yield response;
                    }
                    case "toString" -> "FakeDoclingServeApi";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static DoclingServeApi fakeApiFailure(RuntimeException failure) {
        return (DoclingServeApi) Proxy.newProxyInstance(
                DoclingServeApi.class.getClassLoader(),
                new Class<?>[]{DoclingServeApi.class},
                (proxy, method, arguments) -> {
                    throw failure;
                }
        );
    }

    /** 构造数组顺序与 body reading order 不同的官方模型 fixture。 */
    private static DoclingDocument fixture() {
        List<DoclingDocument.BaseTextItem> texts = List.of(
                paragraph("#/texts/0", "Gateway is healthy", 2),
                title("#/texts/1", "Operations Handbook", 1),
                heading("#/texts/2", "Overview", 1, 1),
                list("#/texts/3", "Check metrics", 2),
                code("#/texts/4", "curl /health", 2)
        );
        return DoclingDocument.builder()
                .schemaName("DoclingDocument")
                .version("1.7.0")
                .name("handbook")
                .body(body(List.of(
                        ref("#/texts/1"),
                        ref("#/texts/2"),
                        ref("#/texts/0"),
                        ref("#/texts/3"),
                        ref("#/tables/0"),
                        ref("#/texts/4")
                )))
                .texts(texts)
                .tables(List.of(table(2)))
                .build();
    }

    private static GroupItem body(List<RefItem> children) {
        return GroupItem.builder()
                .selfRef("#/body")
                .children(children)
                .contentLayer(ContentLayer.BODY)
                .label(GroupLabel.UNSPECIFIED)
                .build();
    }

    private static TitleItem title(String selfRef, String text, int page) {
        return TitleItem.builder()
                .selfRef(selfRef)
                .parent(ref("#/body"))
                .contentLayer(ContentLayer.BODY)
                .label(DocItemLabel.TITLE)
                .orig(text)
                .text(text)
                .prov(provenance(page))
                .build();
    }

    private static SectionHeaderItem heading(
            String selfRef,
            String text,
            int level,
            int page
    ) {
        return SectionHeaderItem.builder()
                .selfRef(selfRef)
                .parent(ref("#/body"))
                .contentLayer(ContentLayer.BODY)
                .label(DocItemLabel.SECTION_HEADER)
                .orig(text)
                .text(text)
                .level(level)
                .prov(provenance(page))
                .build();
    }

    private static TextItem paragraph(String selfRef, String text, int page) {
        return TextItem.builder()
                .selfRef(selfRef)
                .parent(ref("#/body"))
                .contentLayer(ContentLayer.BODY)
                .label(DocItemLabel.PARAGRAPH)
                .orig(text)
                .text(text)
                .prov(provenance(page))
                .build();
    }

    private static TextItem paragraphWithParent(
            String selfRef,
            String text,
            String parentRef,
            int page
    ) {
        return TextItem.builder()
                .selfRef(selfRef)
                .parent(ref(parentRef))
                .contentLayer(ContentLayer.BODY)
                .label(DocItemLabel.PARAGRAPH)
                .orig(text)
                .text(text)
                .prov(provenance(page))
                .build();
    }

    private static ListItem list(String selfRef, String text, int page) {
        return ListItem.builder()
                .selfRef(selfRef)
                .parent(ref("#/body"))
                .contentLayer(ContentLayer.BODY)
                .label(DocItemLabel.LIST_ITEM)
                .orig(text)
                .text(text)
                .enumerated(false)
                .marker("-")
                .prov(provenance(page))
                .build();
    }

    private static CodeItem code(String selfRef, String text, int page) {
        return CodeItem.builder()
                .selfRef(selfRef)
                .parent(ref("#/body"))
                .contentLayer(ContentLayer.BODY)
                .label(DocItemLabel.CODE)
                .orig(text)
                .text(text)
                .codeLanguage("shell")
                .prov(provenance(page))
                .build();
    }

    private static TableItem table(Integer page) {
        TableCell name = TableCell.builder().text("Name").columnHeader(true).build();
        TableCell owner = TableCell.builder().text("Owner").columnHeader(true).build();
        TableCell gateway = TableCell.builder().text("Gateway").build();
        TableCell platform = TableCell.builder().text("Platform").build();
        TableData data = TableData.builder()
                .numRows(2)
                .numCols(2)
                .grid(List.of(name, owner))
                .grid(List.of(gateway, platform))
                .build();
        TableItem.Builder builder = TableItem.builder()
                .selfRef("#/tables/0")
                .parent(ref("#/body"))
                .contentLayer(ContentLayer.BODY)
                .label("table")
                .data(data);
        if (page != null) {
            builder.prov(provenance(page));
        }
        return builder.build();
    }

    private static DoclingDocument.BaseTextItem withoutProvenance(
            DoclingDocument.BaseTextItem item
    ) {
        if (item instanceof TitleItem value) {
            return value.toBuilder().clearProv().build();
        }
        if (item instanceof SectionHeaderItem value) {
            return value.toBuilder().clearProv().build();
        }
        if (item instanceof ListItem value) {
            return value.toBuilder().clearProv().build();
        }
        if (item instanceof CodeItem value) {
            return value.toBuilder().clearProv().build();
        }
        return ((TextItem) item).toBuilder().clearProv().build();
    }

    private static ProvenanceItem provenance(int page) {
        return ProvenanceItem.builder().pageNo(page).build();
    }

    private static RefItem ref(String value) {
        return RefItem.builder().ref(value).build();
    }
}
