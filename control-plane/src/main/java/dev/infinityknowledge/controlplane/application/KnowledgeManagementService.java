package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.ManagementViews;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.spi.management.KnowledgeAdministrationStore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 面向管理控制台的租户级只读服务。
 *
 * <p>该服务只处理授权与 HTTP 读模型映射，持久化查询由
 * {@link KnowledgeAdministrationStore} 完成。</p>
 */
@Service
public final class KnowledgeManagementService {

    private final KnowledgeAdministrationStore store;

    public KnowledgeManagementService(KnowledgeAdministrationStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public ManagementViews.Overview overview(PrincipalContext principal) {
        requireAdmin(principal);
        var value = store.overview(principal.tenantId());
        return new ManagementViews.Overview(
                value.spaces(),
                value.activeDocuments(),
                value.chunks(),
                value.connectors(),
                value.pendingProjections(),
                value.deadProjections(),
                value.evaluationDatasets(),
                value.evaluationRuns()
        );
    }

    public List<ManagementViews.Space> spaces(PrincipalContext principal) {
        requireAdmin(principal);
        return store.spaces(principal.tenantId()).stream()
                .map(value -> new ManagementViews.Space(
                        value.id(),
                        value.name(),
                        value.description(),
                        value.status(),
                        value.version(),
                        value.documentCount(),
                        value.updatedAt()
                ))
                .toList();
    }

    public ManagementViews.Page<ManagementViews.Document> documents(
            PrincipalContext principal,
            String spaceId,
            String status,
            int limit,
            int offset
    ) {
        requireAdmin(principal);
        var page = store.documents(
                principal.tenantId(),
                new KnowledgeAdministrationStore.DocumentFilter(
                        spaceId,
                        status,
                        limit,
                        offset
                )
        );
        List<ManagementViews.Document> items = page.items().stream()
                .map(value -> new ManagementViews.Document(
                        value.id(),
                        value.spaceId(),
                        value.title(),
                        value.sourceType(),
                        value.sourceUri(),
                        value.status(),
                        value.authority(),
                        value.version(),
                        value.activeRevisionId(),
                        value.chunkCount(),
                        value.keywordStatus(),
                        value.vectorStatus(),
                        value.graphStatus(),
                        value.originalFileName(),
                        value.sourceMediaType(),
                        value.sourceContentLength(),
                        value.updatedAt()
                ))
                .toList();
        return new ManagementViews.Page<>(
                items,
                page.limit(),
                page.offset(),
                page.total()
        );
    }

    public List<ManagementViews.Chunk> chunks(
            PrincipalContext principal,
            UUID documentId
    ) {
        requireAdmin(principal);
        return store.chunks(principal.tenantId(), documentId).stream()
                .map(value -> new ManagementViews.Chunk(
                        value.id(),
                        value.ordinal(),
                        value.sectionPath(),
                        value.content(),
                        value.contentHash()
                ))
                .toList();
    }

    public List<ManagementViews.Revision> revisions(
            PrincipalContext principal,
            UUID documentId
    ) {
        requireAdmin(principal);
        return store.revisions(principal.tenantId(), documentId).stream()
                .map(value -> new ManagementViews.Revision(
                        value.revisionId(),
                        value.revisionNumber(),
                        value.contentHash(),
                        value.mediaType(),
                        value.language(),
                        value.parserVersion(),
                        value.createdAt(),
                        value.active(),
                        value.chunkCount()
                ))
                .toList();
    }

    public List<ManagementViews.Chunk> chunks(
            PrincipalContext principal,
            UUID documentId,
            UUID revisionId
    ) {
        requireAdmin(principal);
        return store.chunks(principal.tenantId(), documentId, revisionId).stream()
                .map(value -> new ManagementViews.Chunk(
                        value.id(),
                        value.ordinal(),
                        value.sectionPath(),
                        value.content(),
                        value.contentHash()
                ))
                .toList();
    }

    public List<ManagementViews.Connector> connectors(PrincipalContext principal) {
        requireAdmin(principal);
        return store.connectors(principal.tenantId()).stream()
                .map(value -> new ManagementViews.Connector(
                        value.id(),
                        value.spaceId(),
                        value.type(),
                        value.displayName(),
                        value.status(),
                        value.version(),
                        value.lastRunId(),
                        value.lastRunStatus(),
                        value.lastRunAt(),
                        value.updatedAt()
                ))
                .toList();
    }

    public List<ManagementViews.Trace> traces(PrincipalContext principal, int limit) {
        requireAdmin(principal);
        return store.traces(principal.tenantId(), limit).stream()
                .map(KnowledgeManagementService::trace)
                .toList();
    }

    public ManagementViews.Trace trace(PrincipalContext principal, UUID traceId) {
        requireAdmin(principal);
        return store.trace(principal.tenantId(), traceId)
                .map(KnowledgeManagementService::trace)
                .orElseThrow(() -> new IllegalArgumentException("trace does not exist"));
    }

    private static ManagementViews.Trace trace(
            KnowledgeAdministrationStore.Trace value
    ) {
        return new ManagementViews.Trace(
                value.id(),
                value.requestId(),
                value.principalId(),
                value.totalDurationMs(),
                value.resultCount(),
                value.createdAt(),
                value.steps().stream()
                        .map(step -> new ManagementViews.TraceStep(
                                step.ordinal(),
                                step.name(),
                                step.durationMs(),
                                step.inputCount(),
                                step.outputCount(),
                                step.status()
                        ))
                        .toList()
        );
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
