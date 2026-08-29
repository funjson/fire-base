package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.parser.docling.DoclingServerContract;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Docling 外部 Parser 的部署级配置。
 *
 * @param enabled 是否安装 Docling Runtime 与 PDF Parser；Space 只能选择已安装的 parserId
 * @param docxEnabled 是否额外安装 DOCX Parser；固定部署通过 DOCX Golden 后才能开启
 * @param endpoint Docling Serve 根地址，不含具体转换 API 路径
 * @param apiKey 可选的 {@code X-Api-Key}，不得进入日志、Trace 或空间配置
 * @param deploymentContract 运维固定的 Docling 服务镜像与模型部署契约
 * @param serverContract 固定 lossless JSON 契约，格式为 {@code schemaName@schemaVersion}
 * @param connectTimeout 建立 Docling HTTP 连接的硬超时
 * @param documentTimeout 服务端单文档转换预算，会写入请求
 * @param readTimeout HTTP 响应读取硬超时，必须比转换预算至少多 10 秒
 * @param maximumConcurrentRequests 本进程允许同时占用 Docling 的转换请求数
 */
@ConfigurationProperties("infinity.knowledge.ingestion.parsers.docling")
public record DoclingParserProperties(
        boolean enabled,
        boolean docxEnabled,
        URI endpoint,
        String apiKey,
        String deploymentContract,
        String serverContract,
        Duration connectTimeout,
        Duration documentTimeout,
        Duration readTimeout,
        Integer maximumConcurrentRequests
) {

    /** 为关闭状态补齐无副作用默认值；启用时严格校验全部协议边界。 */
    public DoclingParserProperties {
        endpoint = endpoint == null ? URI.create("http://localhost:5001") : endpoint;
        apiKey = apiKey == null ? "" : apiKey.strip();
        deploymentContract = deploymentContract == null ? "" : deploymentContract.strip();
        serverContract = serverContract == null ? "" : serverContract.strip();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        documentTimeout = documentTimeout == null ? Duration.ofMinutes(2) : documentTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(130) : readTimeout;
        maximumConcurrentRequests = maximumConcurrentRequests == null
                ? 2
                : maximumConcurrentRequests;
        if (docxEnabled && !enabled) {
            throw new IllegalArgumentException(
                    "Docling docxEnabled requires the Docling runtime to be enabled"
            );
        }
        if (enabled) {
            String scheme = endpoint.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Docling endpoint must use http or https");
            }
            if (deploymentContract.isEmpty()) {
                throw new IllegalArgumentException(
                        "Docling deploymentContract is required when enabled"
                );
            }
            if (serverContract.isEmpty()) {
                throw new IllegalArgumentException(
                        "Docling serverContract is required when enabled"
                );
            }
            DoclingServerContract.parse(serverContract);
        }
    }
}
