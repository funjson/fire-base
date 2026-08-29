package dev.infinityknowledge.spi.management;

import dev.infinityknowledge.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理控制台所需的租户级只读数据端口。
 *
 * <p>该端口按管理用例聚合查询，避免 Control Plane 直接依赖 JDBC，
 * 同时不为每张表引入独立 Repository。</p>
 */
public interface KnowledgeAdministrationStore {

    Overview overview(TenantId tenantId);

    List<Space> spaces(TenantId tenantId);

    DocumentPage documents(TenantId tenantId, DocumentFilter filter);

    /** 按租户和文档标识读取独立详情页所需的摘要。 */
    Optional<Document> document(TenantId tenantId, UUID documentId);

    List<Chunk> chunks(TenantId tenantId, UUID documentId);

    List<Revision> revisions(TenantId tenantId, UUID documentId);

    List<Chunk> chunks(TenantId tenantId, UUID documentId, UUID revisionId);

    List<Connector> connectors(TenantId tenantId);

    List<Trace> traces(TenantId tenantId, int limit);

    Optional<Trace> trace(TenantId tenantId, UUID traceId);

    record DocumentFilter(
            String spaceId,
            String status,
            String title,
            String source,
            String keywordStatus,
            String vectorStatus,
            Integer minimumChunkCount,
            Integer maximumChunkCount,
            Instant updatedFrom,
            Instant updatedTo,
            int limit,
            int offset
    ) {
    }

    record Overview(
            long spaces,
            long activeDocuments,
            long chunks,
            long connectors,
            long pendingProjections,
            long deadProjections,
            long evaluationDatasets,
            long evaluationRuns
    ) {
    }

    record Space(
            String id,
            String name,
            String description,
            String status,
            long version,
            long documentCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record Document(
            UUID id,
            String spaceId,
            String title,
            String sourceType,
            String sourceUri,
            String status,
            int authority,
            long version,
            UUID activeRevisionId,
            long chunkCount,
            String keywordStatus,
            String vectorStatus,
            String graphStatus,
            String originalFileName,
            String sourceMediaType,
            Long sourceContentLength,
            Instant updatedAt
    ) {
    }

    record DocumentPage(
            List<Document> items,
            int limit,
            int offset,
            long total
    ) {
        public DocumentPage {
            items = List.copyOf(items);
        }
    }

    record Chunk(
            UUID id,
            int ordinal,
            List<String> sectionPath,
            String content,
            String contentHash
    ) {
        public Chunk {
            sectionPath = List.copyOf(sectionPath);
        }
    }

    record Revision(
            UUID revisionId,
            long revisionNumber,
            String contentHash,
            String mediaType,
            String language,
            String parserVersion,
            Instant createdAt,
            boolean active,
            long chunkCount
    ) {
    }

    record Connector(
            String id,
            String spaceId,
            String type,
            String displayName,
            String status,
            long version,
            UUID lastRunId,
            String lastRunStatus,
            Instant lastRunAt,
            Instant updatedAt
    ) {
    }

    record Trace(
            UUID id,
            UUID requestId,
            String principalId,
            long totalDurationMs,
            int resultCount,
            Instant createdAt,
            List<TraceStep> steps
    ) {
        public Trace {
            steps = List.copyOf(steps);
        }
    }

    record TraceStep(
            int ordinal,
            String name,
            long durationMs,
            int inputCount,
            int outputCount,
            String status
    ) {
    }
}
