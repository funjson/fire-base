package dev.infinityknowledge.ingestion.extraction;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;

import java.util.Objects;

/**
 * 描述一次与存储无关的文档抽取请求。
 *
 * <p>调用方必须先完成来源快照、有界读取和基础字段规范化。本对象只携带
 * Parse、Clean、Chunk 三个阶段共同需要的稳定身份、内容和配置快照，不包含
 * HTTP、对象存储或数据库写入语义。</p>
 */
public final class ExtractionRequest {
    private final TenantId tenantId;
    private final KnowledgeSpaceId spaceId;
    private final DocumentId documentId;
    private final String mediaType;
    private final String fileName;
    private final String language;
    private final String normalizerContract;
    private final byte[] sourceBytes;
    private final String contentHash;
    private final DocumentParseLimits parseLimits;
    private final SpaceDocumentProcessingConfig processingConfig;

    /**
     * 创建一次抽取请求。
     *
     * <p>{@code sourceBytes} 在本次同步执行期间必须视为只读。这里不再复制最大可达
     * 数十 MB 的原件；真正交给 Parser 的输入仍由解析边界执行防御性复制。</p>
     */
    public ExtractionRequest(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            String mediaType,
            String fileName,
            String language,
            String normalizerContract,
            byte[] sourceBytes,
            String contentHash,
            DocumentParseLimits parseLimits,
            SpaceDocumentProcessingConfig processingConfig
    ) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.spaceId = Objects.requireNonNull(spaceId, "spaceId must not be null");
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType must not be null");
        this.fileName = Objects.requireNonNull(fileName, "fileName must not be null");
        this.language = Objects.requireNonNull(language, "language must not be null");
        this.normalizerContract = Objects.requireNonNull(
                normalizerContract,
                "normalizerContract must not be null"
        );
        this.sourceBytes = Objects.requireNonNull(
                sourceBytes,
                "sourceBytes must not be null"
        );
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
        this.parseLimits = Objects.requireNonNull(
                parseLimits,
                "parseLimits must not be null"
        );
        this.processingConfig = Objects.requireNonNull(
                processingConfig,
                "processingConfig must not be null"
        );
        if (!tenantId.equals(processingConfig.tenantId())
                || !spaceId.equals(processingConfig.spaceId())) {
            throw new IllegalArgumentException(
                    "processingConfig belongs to another tenant or space"
            );
        }
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public KnowledgeSpaceId spaceId() {
        return spaceId;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public String mediaType() {
        return mediaType;
    }

    public String fileName() {
        return fileName;
    }

    public String language() {
        return language;
    }

    public String normalizerContract() {
        return normalizerContract;
    }

    /** 返回仅供本次同步抽取读取的来源字节；调用方不得修改。 */
    public byte[] sourceBytes() {
        return sourceBytes;
    }

    public String contentHash() {
        return contentHash;
    }

    public DocumentParseLimits parseLimits() {
        return parseLimits;
    }

    public SpaceDocumentProcessingConfig processingConfig() {
        return processingConfig;
    }
}
