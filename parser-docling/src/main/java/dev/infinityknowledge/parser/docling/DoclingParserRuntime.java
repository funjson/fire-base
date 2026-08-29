package dev.infinityknowledge.parser.docling;

import ai.docling.serve.api.DoclingServeApi;
import dev.infinityknowledge.ingestion.parser.DocumentParser;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * 一个部署级 Docling Serve 客户端及其 PDF、DOCX Parser 工厂。
 *
 * <p>第三方客户端只存在于本模块内部；控制面只取得标准 {@link DocumentParser}，
 * Docling 请求和响应类型不会进入领域对象或空间配置。</p>
 */
public final class DoclingParserRuntime {

    private static final Pattern DEPLOYMENT_CONTRACT = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._/@:+-]{0,255}"
    );

    private final DoclingServeApi api;
    private final String deploymentContract;
    private final DoclingServerContract serverContract;
    private final Duration documentTimeout;
    private final Semaphore conversionBulkhead;

    private DoclingParserRuntime(
            DoclingServeApi api,
            String deploymentContract,
            DoclingServerContract serverContract,
            Duration documentTimeout,
            Semaphore conversionBulkhead
    ) {
        this.api = api;
        this.deploymentContract = deploymentContract;
        this.serverContract = serverContract;
        this.documentTimeout = documentTimeout;
        this.conversionBulkhead = conversionBulkhead;
    }

    /**
     * 连接部署固定的 Docling Serve，不开启 SDK 的请求或响应日志。
     *
     * <p>响应中可能包含全文和模型产物，因此这里有意不调用 SDK 的
     * {@code logRequests}/{@code logResponses}。</p>
     */
    public static DoclingParserRuntime connect(
            URI endpoint,
            String apiKey,
            String deploymentContract,
            String serverContract,
            Duration connectTimeout,
            Duration documentTimeout,
            Duration readTimeout,
            int maximumConcurrentRequests
    ) {
        Objects.requireNonNull(endpoint, "Docling endpoint must not be null");
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Docling endpoint must use http or https");
        }
        Duration normalizedConnectTimeout = requireTimeout(
                "connectTimeout",
                connectTimeout,
                Duration.ofMinutes(1)
        );
        Duration normalizedDocumentTimeout = requireTimeout(
                "documentTimeout",
                documentTimeout,
                Duration.ofMinutes(10)
        );
        Duration normalizedReadTimeout = requireTimeout(
                "readTimeout",
                readTimeout,
                Duration.ofMinutes(11)
        );
        if (normalizedReadTimeout.compareTo(
                normalizedDocumentTimeout.plusSeconds(10)
        ) < 0) {
            throw new IllegalArgumentException(
                    "Docling readTimeout must exceed documentTimeout by at least 10 seconds"
            );
        }
        if (maximumConcurrentRequests < 1 || maximumConcurrentRequests > 32) {
            throw new IllegalArgumentException(
                    "Docling maximumConcurrentRequests must be between 1 and 32"
            );
        }
        DoclingServeApi.DoclingApiBuilder<?, ?> builder = DoclingServeApi.builder()
                .baseUrl(endpoint)
                .connectTimeout(normalizedConnectTimeout)
                .readTimeout(normalizedReadTimeout);
        String normalizedApiKey = apiKey == null ? "" : apiKey.strip();
        if (!normalizedApiKey.isEmpty()) {
            builder.apiKey(normalizedApiKey);
        }
        return new DoclingParserRuntime(
                builder.build(),
                requireDeploymentContract(deploymentContract),
                DoclingServerContract.parse(serverContract),
                normalizedDocumentTimeout,
                new Semaphore(maximumConcurrentRequests, true)
        );
    }

    /** 返回只处理 PDF 的 Docling Parser。 */
    public DocumentParser pdfParser() {
        return new DoclingDocumentParser(
                api,
                DoclingDocumentParser.SourceFormat.PDF,
                deploymentContract,
                serverContract,
                documentTimeout,
                conversionBulkhead
        );
    }

    /** 返回只处理 DOCX 的 Docling Parser。 */
    public DocumentParser docxParser() {
        return new DoclingDocumentParser(
                api,
                DoclingDocumentParser.SourceFormat.DOCX,
                deploymentContract,
                serverContract,
                documentTimeout,
                conversionBulkhead
        );
    }

    private static Duration requireTimeout(String name, Duration value, Duration maximum) {
        Duration timeout = Objects.requireNonNull(value, "Docling " + name + " must not be null");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Docling " + name + " is outside the supported range"
            );
        }
        return timeout;
    }

    /** 部署契约只能是运维固定的版本或镜像摘要，不得携带空白和任意日志内容。 */
    static String requireDeploymentContract(String value) {
        Objects.requireNonNull(value, "Docling deploymentContract must not be null");
        String normalized = value.strip();
        if (!DEPLOYMENT_CONTRACT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Docling deploymentContract has invalid format");
        }
        return normalized;
    }
}
