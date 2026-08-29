package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.config.SpaceDocumentProcessingConfigResolver;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.extraction.ExtractionRequest;
import dev.infinityknowledge.runtime.ingestion.DocumentPublicationService;
import dev.infinityknowledge.runtime.ingestion.DocumentPublicationService.Publication;
import dev.infinityknowledge.runtime.ingestion.DocumentPublicationService.PublicationRequest;
import dev.infinityknowledge.spi.connector.ConnectorWriteFence;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * 统一执行文档身份、解析、清洗、Chunk、修订组装与事务发布。
 *
 * <p>本类型承接同步 Markdown 与 Connector 输入；异步多文件任务直接在 Runtime 复用
 * 同一个 {@link ExtractionEngine} 与 {@link DocumentPublicationService}。不同入口不会
 * 再复制处理指纹、修订组装和事务发布算法。</p>
 */
@Service
public final class DocumentIngestionPipeline {
    private final KnowledgeCatalog catalog;
    private final DocumentPublicationService publicationService;
    private final ExtractionEngine extractionEngine;
    private final SpaceDocumentProcessingConfigResolver configResolver;
    private final FileIngestionProperties parserLimits;

    /**
     * 创建来源无关的摄取主线。
     */
    public DocumentIngestionPipeline(
            KnowledgeCatalog catalog,
            DocumentPublicationService publicationService,
            ExtractionEngine extractionEngine,
            SpaceDocumentProcessingConfigResolver configResolver,
            FileIngestionProperties parserLimits
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.publicationService = Objects.requireNonNull(
                publicationService,
                "publicationService must not be null"
        );
        this.extractionEngine = Objects.requireNonNull(
                extractionEngine,
                "extractionEngine must not be null"
        );
        this.configResolver = Objects.requireNonNull(
                configResolver,
                "configResolver must not be null"
        );
        this.parserLimits = Objects.requireNonNull(
                parserLimits,
                "parserLimits must not be null"
        );
    }

    /**
     * 发布一个已经完成来源级规范化和有界读取的文档。
     *
     * @param input 不包含 HTTP 或对象存储实现细节的文档输入
     * @return 事务写入结果与解析计数
     */
    public Publication publish(DocumentInput input) {
        Objects.requireNonNull(input, "input must not be null");
        DocumentId documentId = catalog.findDocumentId(
                input.tenantId(),
                input.spaceId(),
                input.source().connectorId(),
                input.source().externalId()
        ).orElseGet(() -> IngestionIdentity.documentId(
                input.tenantId(),
                input.spaceId(),
                input.source()
        ));
        var config = configResolver.resolve(input.tenantId(), input.spaceId());
        var extraction = extractionEngine.extract(new ExtractionRequest(
                input.tenantId(),
                input.spaceId(),
                documentId,
                input.mediaType(),
                input.fileName(),
                input.language(),
                input.normalizerContract(),
                input.sourceBytes(),
                input.contentHash(),
                parserLimits.limits(),
                config
        ));
        return publicationService.publish(
                new PublicationRequest(
                        documentId,
                        input.tenantId(),
                        input.spaceId(),
                        input.source(),
                        input.title(),
                        input.language(),
                        input.authority(),
                        input.metadata(),
                        config.chunker().providerId(),
                        null,
                        input.connectorWriteFence()
                ),
                extraction,
                config.version(),
                config.processingContract()
        );
    }

    /**
     * 表示进入统一主线前已完成来源级校验的有界输入。
     *
     * <p>{@code sourceBytes} 在单次同步调用期间视为只读，避免对最大 25MB 原件再次复制。</p>
     */
    public record DocumentInput(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            SourceDescriptor source,
            String title,
            String mediaType,
            String fileName,
            String language,
            int authority,
            Map<String, String> metadata,
            String normalizerContract,
            byte[] sourceBytes,
            String contentHash,
            ConnectorWriteFence connectorWriteFence
    ) {

        /**
         * 校验统一主线所需的稳定身份、解析输入与完整性元数据。
         */
        public DocumentInput {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(spaceId, "spaceId must not be null");
            Objects.requireNonNull(source, "source must not be null");
            title = DomainChecks.requiredText(title, "title", 512);
            mediaType = DomainChecks.requiredText(mediaType, "mediaType", 128)
                    .toLowerCase(java.util.Locale.ROOT);
            fileName = DomainChecks.requiredText(fileName, "fileName", 512);
            language = IngestionIdentity.normalizeLanguageTag(language);
            if (authority < 0 || authority > 100) {
                throw new IllegalArgumentException("authority must be between 0 and 100");
            }
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
            normalizerContract = DomainChecks.requiredText(
                    normalizerContract,
                    "normalizerContract",
                    128
            );
            Objects.requireNonNull(sourceBytes, "sourceBytes must not be null");
            if (sourceBytes.length == 0) {
                throw new IllegalArgumentException("sourceBytes must not be empty");
            }
            contentHash = DomainChecks.requiredText(contentHash, "contentHash", 64)
                    .toLowerCase(java.util.Locale.ROOT);
            if (!contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("contentHash must be lowercase SHA-256");
            }
            if (connectorWriteFence != null
                    && !tenantId.equals(connectorWriteFence.tenantId())) {
                throw new IllegalArgumentException(
                        "connector write fence belongs to another tenant"
                );
            }
        }
    }

}
