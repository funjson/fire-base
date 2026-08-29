package dev.infinityknowledge.parser.docling;

import ai.docling.core.DoclingDocument;
import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParseInput;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.ingestion.parser.DocumentParser;
import dev.infinityknowledge.ingestion.parser.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 以合成 PDF、DOCX 调用固定版本的真实 Docling Serve，验证 Java 协议与阅读顺序。
 *
 * <p>该测试不会访问公网来源；所有文档字节均在内存生成，并且只把稳定断言写入
 * 测试报告，不输出原文或服务端 lossless JSON。</p>
 */
@EnabledIfEnvironmentVariable(named = "RUN_DOCLING_TESTS", matches = "true")
class DoclingServeGoldenIT {

    private static final String DEPLOYMENT_CONTRACT =
            "ghcr.io/docling-project/docling-serve-cpu:v1.20.0@sha256:"
                    + "419967009a6b507bf25380132335cf8b354e6ce9df4d40f46a612de2e9ddcb88";
    private static final String SERVER_CONTRACT = "DoclingDocument@1.10.0";

    @Test
    void parsesSyntheticPdfThroughPinnedServeContract() throws Exception {
        ParsedDocument parsed = runtime().pdfParser().parse(input(
                "application/pdf",
                "extraction-golden.pdf",
                pdfGolden()
        ));

        assertGolden(parsed, "docling-serve-pdf");
        assertThat(parsed.elements())
                .allSatisfy(element -> assertThat(element.attributes())
                        .containsKey("pageNumber"));
    }

    @Test
    void rejectsKnownDocxTableLossFromPinnedServeContract() throws Exception {
        AtomicReference<DoclingDocument> rawDocument = new AtomicReference<>();
        DocumentParser parser = capturingParser(
                DoclingDocumentParser.SourceFormat.DOCX,
                rawDocument
        );
        Throwable thrown = catchThrowable(() -> parser.parse(input(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "extraction-golden.docx",
                docxGolden()
        )));

        assertThat(thrown).isInstanceOf(DocumentParseException.class);
        DocumentParseException failure = (DocumentParseException) thrown;
        assertThat(failure).hasMessage(
                "DOCLING_SOURCE_COVERAGE_MISMATCH: DOCX table content was not preserved"
        );
        assertThat(rawDocument.get()).isNotNull();
        boolean rawResponseContainsTableCell = rawDocument.get().getTexts().stream()
                .map(item -> item.getText() == null ? "" : item.getText())
                .anyMatch(text -> text.contains("OWNER PLATFORM"));
        assertThat(rawResponseContainsTableCell)
                .as("固定部署已知缺陷必须由覆盖门禁稳定识别，不能静默发布")
                .isFalse();
        assertThat(rawDocument.get().getTables())
                .as("固定部署的原始 lossless JSON 确认没有伪造 TABLE")
                .isEmpty();
    }

    private static DoclingParserRuntime runtime() {
        String endpoint = environment("KNOWLEDGE_DOCLING_ENDPOINT", "http://localhost:5001");
        String serverContract = environment(
                "KNOWLEDGE_DOCLING_SERVER_CONTRACT",
                SERVER_CONTRACT
        );
        return DoclingParserRuntime.connect(
                URI.create(endpoint),
                environment("KNOWLEDGE_DOCLING_API_KEY", ""),
                DEPLOYMENT_CONTRACT,
                serverContract,
                Duration.ofSeconds(10),
                Duration.ofMinutes(4),
                Duration.ofSeconds(250),
                1
        );
    }

    /** 捕获真实响应只供结构断言使用，不记录正文或序列化 lossless JSON。 */
    private static DocumentParser capturingParser(
            DoclingDocumentParser.SourceFormat format,
            AtomicReference<DoclingDocument> captured
    ) {
        URI endpoint = URI.create(environment(
                "KNOWLEDGE_DOCLING_ENDPOINT",
                "http://localhost:5001"
        ));
        DoclingServeApi realApi = DoclingServeApi.builder()
                .baseUrl(endpoint)
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(250))
                .build();
        DoclingServeApi capturingApi = (DoclingServeApi) Proxy.newProxyInstance(
                DoclingServeApi.class.getClassLoader(),
                new Class<?>[]{DoclingServeApi.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "convertSource" -> {
                        var response = realApi.convertSource(
                                (ConvertDocumentRequest) arguments[0]
                        );
                        if (response instanceof InBodyConvertDocumentResponse inBody
                                && inBody.getDocument() != null) {
                            captured.set(inBody.getDocument().getJsonContent());
                        }
                        yield response;
                    }
                    case "toString" -> "CapturingDoclingServeApi";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        return new DoclingDocumentParser(
                capturingApi,
                format,
                DEPLOYMENT_CONTRACT,
                DoclingServerContract.parse(SERVER_CONTRACT),
                Duration.ofMinutes(4),
                new Semaphore(1, true)
        );
    }

    private static DocumentParseInput input(
            String mediaType,
            String fileName,
            byte[] source
    ) {
        return new DocumentParseInput(
                UUID.randomUUID(),
                mediaType,
                fileName,
                source,
                DocumentParseLimits.defaults()
        );
    }

    private static void assertGolden(ParsedDocument parsed, String parserId) {
        assertThat(parsed.parserId()).isEqualTo(parserId);
        assertThat(parsed.parserVersion())
                .contains("docling-java-0.5.3")
                .contains(DEPLOYMENT_CONTRACT)
                .contains(SERVER_CONTRACT);
        assertThat(parsed.elements()).isNotEmpty();
        assertThat(parsed.elements().stream().map(KnowledgeElement::ordinal).toList())
                .containsExactlyElementsOf(ordinals(parsed.elements().size()));

        String artifact = parsed.artifact().text().replaceAll("\\s+", " ");
        assertThat(artifact)
                .contains("EXTRACTION GOLDEN RUNBOOK")
                .contains("START MARKER ALPHA")
                .contains("OWNER PLATFORM TEAM")
                .contains("END MARKER OMEGA");
        assertThat(artifact.indexOf("START MARKER ALPHA"))
                .isLessThan(artifact.indexOf("OWNER PLATFORM TEAM"));
        assertThat(artifact.indexOf("OWNER PLATFORM TEAM"))
                .isLessThan(artifact.indexOf("END MARKER OMEGA"));
        assertThat(parsed.provenance()).hasSameSizeAs(parsed.elements());
    }

    private static List<Integer> ordinals(int size) {
        return java.util.stream.IntStream.range(0, size).boxed().toList();
    }

    private static byte[] pdfGolden() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                content.newLineAtOffset(72, 720);
                content.showText("EXTRACTION GOLDEN RUNBOOK");
                content.setLeading(28);
                content.newLine();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.showText("START MARKER ALPHA");
                content.newLine();
                content.showText("OWNER PLATFORM TEAM");
                content.newLine();
                content.showText("END MARKER OMEGA");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docxGolden() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            paragraph(document, "Title", "EXTRACTION GOLDEN RUNBOOK");
            paragraph(document, "Heading1", "START MARKER ALPHA");
            XWPFTable table = document.createTable(2, 2);
            table.setStyleID("TableGrid");
            table.getRow(0).getCell(0).setText("CONTROL");
            table.getRow(0).getCell(1).setText("VALUE");
            table.getRow(1).getCell(0).setText("OWNER PLATFORM");
            table.getRow(1).getCell(1).setText("TEAM");
            paragraph(document, null, "END MARKER OMEGA");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static void paragraph(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        if (style != null) {
            paragraph.setStyle(style);
        }
        paragraph.createRun().setText(text);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
