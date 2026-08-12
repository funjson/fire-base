package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.MarkdownDocumentRequest;
import dev.infinityknowledge.controlplane.api.MarkdownDocumentResponse;
import dev.infinityknowledge.controlplane.config.IngestionProperties;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.HeadingAwareChunker;
import dev.infinityknowledge.ingestion.MarkdownElementParser;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.vector.VectorProjectionService;
import dev.infinityknowledge.spi.connector.SourceRecord;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.ConnectorWriteFence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 编排 API Markdown 文档的规范化、结构解析、切分与事务发布。
 */
@Service
public class MarkdownIngestionService {

    private final KnowledgeCatalog catalog;
    private final KnowledgeWriter writer;
    private final Clock clock;
    private final VectorProjectionService vectorProjectionService;
    private final MarkdownElementParser parser;
    private final HeadingAwareChunker chunker;
    private final IngestionProperties ingestionProperties;

    /**
     * 创建 Markdown 摄取服务。
     *
     * @param catalog 修订目录
     * @param writer 知识写入端口
     * @param clock UTC 时钟
     */
    public MarkdownIngestionService(
            KnowledgeCatalog catalog,
            KnowledgeWriter writer,
            ObjectProvider<VectorProjectionService> vectorProjectionService,
            MarkdownElementParser parser,
            HeadingAwareChunker chunker,
            IngestionProperties ingestionProperties,
            Clock clock
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.vectorProjectionService = vectorProjectionService.getIfAvailable();
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
        this.ingestionProperties = Objects.requireNonNull(
                ingestionProperties,
                "ingestionProperties must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 将 Markdown 发布为当前租户中的活动知识文档。
     *
     * @param principal 已认证主体
     * @param request 文档请求
     * @return 写入结果
     */
    public MarkdownDocumentResponse ingest(
            PrincipalContext principal,
            MarkdownDocumentRequest request
    ) {
        requireAdmin(principal);
        var spaceId = new KnowledgeSpaceId(request.spaceId());
        return ingestSource(
                principal,
                spaceId,
                new SourceDescriptor(
                        apiUploadConnectorId(spaceId),
                        SourceType.API,
                        request.externalId(),
                        request.sourceUri(),
                        Map.of()
                ),
                request.title(),
                request.content(),
                "text/markdown",
                request.language(),
                request.authority(),
                request.metadata() == null ? Map.of() : request.metadata()
        );
    }

    /**
     * 将连接器输出的 Markdown 记录写入同一事务知识模型。
     *
     * @param principal 已认证租户管理员
     * @param spaceId 目标知识空间
     * @param record 来源记录
     * @param authority 权威等级
     * @return 写入结果
     */
    public MarkdownDocumentResponse ingestSourceRecord(
            ConnectorStateStore.SynchronizationLease lease,
            KnowledgeSpaceId spaceId,
            SourceRecord record,
            int authority
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(record, "record must not be null");
        if (record.deleted()) {
            throw new IllegalArgumentException(
                    "deleted source records require projection-aware reconciliation"
            );
        }
        String language = record.metadata().getOrDefault("language", "zh-CN");
        return ingestSource(
                lease.principal(),
                spaceId,
                record.source(),
                record.title(),
                record.content(),
                record.mediaType(),
                language,
                authority,
                record.metadata(),
                ConnectorWriteFence.from(lease)
        );
    }

    /**
     * 规范化并发布来自任意 Markdown 来源的活动修订。
     */
    private MarkdownDocumentResponse ingestSource(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            SourceDescriptor source,
            String title,
            String content,
            String mediaType,
            String language,
            int authority,
            Map<String, String> metadata
    ) {
        return ingestSource(
                principal, spaceId, source, title, content, mediaType, language,
                authority, metadata, null
        );
    }

    private MarkdownDocumentResponse ingestSource(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            SourceDescriptor source,
            String title,
            String content,
            String mediaType,
            String language,
            int authority,
            Map<String, String> metadata,
            ConnectorWriteFence connectorWriteFence
    ) {
        requireAdmin(principal);
        if (!"text/markdown".equalsIgnoreCase(mediaType)) {
            throw new IllegalArgumentException("only Markdown source records are supported");
        }
        String normalizedMediaType = "text/markdown";
        if (!ingestionProperties.allowsSourceUri(source.uri())) {
            throw new IllegalArgumentException("sourceUri scheme is not allowed");
        }
        String normalizedLanguage = normalizeLanguageTag(language);
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        DocumentId documentId = catalog.findDocumentId(
                principal.tenantId(),
                spaceId,
                source.connectorId(),
                source.externalId()
        ).orElseGet(() -> documentId(principal, spaceId, source));
        String contentHash = sha256(normalized);
        String processorVersion = processorVersion();
        UUID revisionId = revisionId(
                documentId,
                contentHash,
                normalizedMediaType,
                normalizedLanguage,
                processorVersion
        );
        var now = clock.instant();
        var document = new KnowledgeDocument(
                documentId,
                principal.tenantId(),
                spaceId,
                title,
                source,
                DocumentStatus.ACTIVE,
                authority,
                metadata,
                now,
                now
        );
        var revision = new DocumentRevision(
                revisionId,
                documentId,
                contentHash,
                normalizedMediaType,
                normalizedLanguage,
                processorVersion,
                now
        );
        var elements = parser.parse(revisionId, normalized);
        var chunks = chunker.chunk(
                principal.tenantId(),
                spaceId,
                documentId,
                revisionId,
                elements
        );
        var result = writer.write(new KnowledgeWriteBatch(
                document, revision, elements, chunks, null, connectorWriteFence
        ));
        String vectorStatus = "SKIPPED";
        List<String> warnings = List.of("VECTOR_PROJECTION_DISABLED");
        if (vectorProjectionService != null) {
            vectorStatus = result.changed() ? "QUEUED" : "UNCHANGED";
            warnings = List.of();
        }
        return new MarkdownDocumentResponse(
                result.documentId().value(),
                result.revisionId(),
                result.changed(),
                elements.size(),
                result.chunkCount(),
                vectorStatus,
                warnings
        );
    }

    /**
     * 计算规范化正文指纹。
     */
    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Creates a deterministic identity for the complete immutable revision contract.
     */
    private static UUID revisionId(
            DocumentId documentId,
            String contentHash,
            String mediaType,
            String language,
            String parserVersion
    ) {
        String identity = String.join(
                "\u001F",
                documentId.value().toString(),
                contentHash,
                mediaType,
                language,
                parserVersion
        );
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Canonicalizes the BCP 47 tag used by filtering and immutable revision identity.
     */
    private static String normalizeLanguageTag(String language) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        try {
            return new Locale.Builder()
                    .setLanguageTag(language.strip())
                    .build()
                    .toLanguageTag();
        } catch (IllformedLocaleException invalidLanguage) {
            throw new IllegalArgumentException(
                    "language must be a well-formed BCP 47 tag",
                    invalidLanguage
            );
        }
    }

    /**
     * Includes chunking budgets so changing ingestion behavior creates fresh chunks.
     */
    private String processorVersion() {
        return String.join(
                ":",
                MarkdownElementParser.VERSION,
                HeadingAwareChunker.VERSION,
                Integer.toString(ingestionProperties.targetChunkCharacters()),
                Integer.toString(ingestionProperties.maximumChunkCharacters())
        );
    }

    /**
     * 为新的来源身份生成可重复的空间级文档标识。
     */
    private static DocumentId documentId(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            SourceDescriptor source
    ) {
        String identity = String.join(
                ":",
                principal.tenantId().value(),
                spaceId.value(),
                source.connectorId(),
                source.externalId()
        );
        return new DocumentId(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))
        );
    }

    /**
     * API 上传连接器按知识空间保持稳定，禁止创建空间时迁移既有连接器。
     */
    private static String apiUploadConnectorId(KnowledgeSpaceId spaceId) {
        return "api-upload:" + spaceId.value();
    }

    /**
     * 限制写入入口仅供租户管理员或系统主体使用。
     */
    private static void requireAdmin(PrincipalContext principal) {
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
