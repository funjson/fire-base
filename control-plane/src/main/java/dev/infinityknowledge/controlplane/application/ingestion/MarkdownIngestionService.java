package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.controlplane.api.document.MarkdownDocumentRequest;
import dev.infinityknowledge.controlplane.api.document.MarkdownDocumentResponse;
import dev.infinityknowledge.controlplane.config.IngestionProperties;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.spi.connector.SourceRecord;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.connector.ConnectorWriteFence;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 编排 API Markdown 文档的规范化、结构解析、切分与事务发布。
 */
@Service
public class MarkdownIngestionService {

    private final DocumentIngestionPipeline pipeline;
    private final IngestionProperties ingestionProperties;

    /**
     * 创建 Markdown 摄取服务。
     *
     * @param pipeline 统一文档摄取主线
     * @param ingestionProperties 来源校验配置
     */
    public MarkdownIngestionService(
            DocumentIngestionPipeline pipeline,
            IngestionProperties ingestionProperties
    ) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline must not be null");
        this.ingestionProperties = Objects.requireNonNull(
                ingestionProperties,
                "ingestionProperties must not be null"
        );
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
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        byte[] sourceBytes = normalized.getBytes(StandardCharsets.UTF_8);
        var publication = pipeline.publish(new DocumentIngestionPipeline.DocumentInput(
                principal.tenantId(),
                spaceId,
                source,
                title,
                normalizedMediaType,
                source.externalId(),
                language,
                authority,
                metadata,
                DocumentProcessingContractFactory.MARKDOWN_NORMALIZER_CONTRACT,
                sourceBytes,
                IngestionIdentity.sha256(sourceBytes),
                connectorWriteFence
        ));
        var result = publication.writeResult();
        ProjectionSchedulingView projection = ProjectionSchedulingView.from(result);
        return new MarkdownDocumentResponse(
                result.documentId().value(),
                result.revisionId(),
                result.changed(),
                publication.elementCount(),
                result.chunkCount(),
                projection.vectorStatus(),
                projection.warnings()
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
