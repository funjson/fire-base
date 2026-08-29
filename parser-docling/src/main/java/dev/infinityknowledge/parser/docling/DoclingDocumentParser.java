package dev.infinityknowledge.parser.docling;

import ai.docling.core.DoclingDocument;
import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ConvertDocumentOptions;
import ai.docling.serve.api.convert.request.options.ImageRefMode;
import ai.docling.serve.api.convert.request.options.InputFormat;
import ai.docling.serve.api.convert.request.options.OcrEngine;
import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.request.options.PdfBackend;
import ai.docling.serve.api.convert.request.options.ProcessingPipeline;
import ai.docling.serve.api.convert.request.options.TableFormerMode;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.request.target.InBodyTarget;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import ai.docling.serve.api.convert.response.DocumentResponse;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParseInput;
import dev.infinityknowledge.ingestion.parser.DocumentParser;
import dev.infinityknowledge.ingestion.parser.OpenXmlPackageGuard;
import dev.infinityknowledge.ingestion.parser.ParsedDocument;
import dev.infinityknowledge.ingestion.parser.ParserOutputCapability;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * 通过 Docling Serve lossless JSON 解析 PDF 或 DOCX 的外部 Parser。
 *
 * <p>每个实例只绑定一种来源格式和一个固定服务端 Schema 契约。任何传输失败、
 * partial 响应、Schema 漂移或引用损坏都会明确失败；本 Adapter 不回退内置 Parser，
 * 避免在同一处理版本下产生不同语义。</p>
 */
public final class DoclingDocumentParser implements DocumentParser {

    /** Maven Central 实际发布并通过编译验证的官方 Docling Java 版本。 */
    public static final String DOCLING_JAVA_VERSION = "0.5.3";

    private static final String MAPPER_VERSION = "reading-order-v2-docx-coverage-v1";
    /** 任何会影响解析结果的请求选项变化都必须提升该版本。 */
    private static final String REQUEST_PROFILE_VERSION = "request-profile-v1";

    private final DoclingServeApi api;
    private final SourceFormat sourceFormat;
    private final String deploymentContract;
    private final DoclingServerContract serverContract;
    private final Duration documentTimeout;
    private final Semaphore conversionBulkhead;
    private final DoclingDocumentMapper mapper;

    DoclingDocumentParser(
            DoclingServeApi api,
            SourceFormat sourceFormat,
            String deploymentContract,
            DoclingServerContract serverContract,
            Duration documentTimeout,
            Semaphore conversionBulkhead
    ) {
        this.api = Objects.requireNonNull(api, "DoclingServeApi must not be null");
        this.sourceFormat = Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
        this.deploymentContract = DoclingParserRuntime.requireDeploymentContract(
                deploymentContract
        );
        this.serverContract = Objects.requireNonNull(
                serverContract,
                "serverContract must not be null"
        );
        this.documentTimeout = Objects.requireNonNull(
                documentTimeout,
                "documentTimeout must not be null"
        );
        this.conversionBulkhead = Objects.requireNonNull(
                conversionBulkhead,
                "conversionBulkhead must not be null"
        );
        this.mapper = new DoclingDocumentMapper(serverContract);
    }

    @Override
    public String id() {
        return sourceFormat.parserId;
    }

    /** 客户端、固定请求、服务部署、服务端 Schema 和映射规则共同构成 Parser 契约。 */
    @Override
    public String version() {
        return "docling-java-" + DOCLING_JAVA_VERSION
                + '-' + REQUEST_PROFILE_VERSION
                + '-' + deploymentContract
                + '-' + serverContract.value()
                + '-' + MAPPER_VERSION;
    }

    @Override
    public String canonicalMediaType() {
        return sourceFormat.canonicalMediaType;
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return Set.of(sourceFormat.canonicalMediaType);
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of(sourceFormat.extension);
    }

    /**
     * PDF 额外保证页码；DOCX 不虚构页面 Provenance。两种格式都保留标题层级和
     * 供检索使用的扁平表格文本，但当前不声明 bbox、原生产物或表格行列领域模型。
     */
    @Override
    public Set<ParserOutputCapability> outputCapabilities() {
        if (sourceFormat == SourceFormat.PDF) {
            return Set.of(
                    ParserOutputCapability.STANDARD_ELEMENTS,
                    ParserOutputCapability.HIERARCHY,
                    ParserOutputCapability.PAGE_NUMBER,
                    ParserOutputCapability.FLAT_TABLE_TEXT
            );
        }
        return Set.of(
                ParserOutputCapability.STANDARD_ELEMENTS,
                ParserOutputCapability.HIERARCHY,
                ParserOutputCapability.FLAT_TABLE_TEXT
        );
    }

    /** 请求 lossless JSON，并在映射前严格验证响应为完整成功。 */
    @Override
    public ParsedDocument parse(DocumentParseInput input) {
        requireCompatibleInput(input);
        byte[] sourceBytes = input.sourceBytes();
        if (sourceFormat == SourceFormat.DOCX) {
            OpenXmlPackageGuard.verify(sourceBytes, input.limits());
        }
        if (!conversionBulkhead.tryAcquire()) {
            throw new DocumentParseException(
                    "DOCLING_BUSY: Docling conversion concurrency limit reached"
            );
        }
        ConvertDocumentResponse rawResponse;
        try {
            try {
                rawResponse = api.convertSource(request(input, sourceBytes));
            } catch (RuntimeException failure) {
                throw new DocumentParseException(
                        "DOCLING_TRANSPORT_FAILED: Docling Serve request failed"
                );
            }
        } finally {
            conversionBulkhead.release();
        }
        InBodyConvertDocumentResponse response = requireSuccessfulResponse(rawResponse);
        DocumentResponse documentResponse = response.getDocument();
        if (documentResponse == null || documentResponse.getJsonContent() == null) {
            throw new DocumentParseException(
                    "DOCLING_LOSSLESS_JSON_MISSING: response did not contain json_content"
            );
        }
        DoclingDocument document = documentResponse.getJsonContent();
        ParsedDocument parsed = mapper.map(
                id(),
                version(),
                input,
                document,
                sourceFormat == SourceFormat.PDF
        );
        if (sourceFormat == SourceFormat.DOCX) {
            DocxSourceCoverage.requireCovered(sourceBytes, parsed);
        }
        return parsed;
    }

    private ConvertDocumentRequest request(DocumentParseInput input, byte[] sourceBytes) {
        FileSource source = FileSource.builder()
                .filename(input.fileName())
                .base64String(Base64.getEncoder().encodeToString(sourceBytes))
                .build();
        ConvertDocumentOptions options = ConvertDocumentOptions.builder()
                .fromFormat(sourceFormat.doclingInputFormat)
                .toFormat(OutputFormat.JSON)
                .imageExportMode(ImageRefMode.PLACEHOLDER)
                .doOcr(true)
                .forceOcr(false)
                .ocrEngine(OcrEngine.AUTO)
                .clearOcrLang()
                .pdfBackend(PdfBackend.DLPARSE_V4)
                .tableMode(TableFormerMode.ACCURATE)
                .tableCellMatching(true)
                .pipeline(ProcessingPipeline.STANDARD)
                .pageRange(List.of(1, input.limits().maximumPages()))
                .abortOnError(true)
                .doTableStructure(true)
                .includeImages(false)
                .imagesScale(2.0)
                .doCodeEnrichment(false)
                .doFormulaEnrichment(false)
                .doPictureClassification(false)
                .doPictureDescription(false)
                .documentTimeout(documentTimeout)
                .build();
        return ConvertDocumentRequest.builder()
                .source(source)
                .options(options)
                .target(InBodyTarget.builder().build())
                .build();
    }

    private static InBodyConvertDocumentResponse requireSuccessfulResponse(
            ConvertDocumentResponse rawResponse
    ) {
        if (!(rawResponse instanceof InBodyConvertDocumentResponse response)) {
            throw new DocumentParseException(
                    "DOCLING_RESPONSE_TYPE_INVALID: expected in-body response"
            );
        }
        String status = response.getStatus() == null
                ? ""
                : response.getStatus().strip().toLowerCase(Locale.ROOT);
        if ("partial_success".equals(status) || "partial".equals(status)) {
            throw new DocumentParseException(
                    "DOCLING_PARTIAL_RESPONSE: partial conversion is not publishable"
            );
        }
        if (!"success".equals(status)) {
            throw new DocumentParseException(
                    "DOCLING_CONVERSION_FAILED: response status was not success"
            );
        }
        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            throw new DocumentParseException(
                    "DOCLING_RESPONSE_ERRORS: successful response contained errors"
            );
        }
        return response;
    }

    private void requireCompatibleInput(DocumentParseInput input) {
        String mediaType = input.mediaType().strip().toLowerCase(Locale.ROOT);
        int parameter = mediaType.indexOf(';');
        if (parameter >= 0) {
            mediaType = mediaType.substring(0, parameter).strip();
        }
        if (!sourceFormat.canonicalMediaType.equals(mediaType)) {
            throw new DocumentParseException(
                    "DOCLING_SOURCE_FORMAT_MISMATCH: selected parser does not match media type"
            );
        }
    }

    /** Docling 实例绑定的唯一来源格式。 */
    enum SourceFormat {
        PDF(
                "docling-serve-pdf",
                "application/pdf",
                ".pdf",
                InputFormat.PDF
        ),
        DOCX(
                "docling-serve-docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ".docx",
                InputFormat.DOCX
        );

        private final String parserId;
        private final String canonicalMediaType;
        private final String extension;
        private final InputFormat doclingInputFormat;

        SourceFormat(
                String parserId,
                String canonicalMediaType,
                String extension,
                InputFormat doclingInputFormat
        ) {
            this.parserId = parserId;
            this.canonicalMediaType = canonicalMediaType;
            this.extension = extension;
            this.doclingInputFormat = doclingInputFormat;
        }
    }
}
