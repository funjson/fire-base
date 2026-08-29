package dev.infinityknowledge.controlplane.application.projection;

import dev.infinityknowledge.controlplane.api.projection.ProjectionJobResponse;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionJobStatusStore;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 租户级投影状态查看与显式死信重试服务。
 */
@Service
public class ProjectionAdministrationService {

    private final ProjectionJobStatusStore statusStore;
    private final Clock clock;
    private final Set<ProjectionType> configuredTypes;

    /**
     * 创建投影管理服务。
     */
    public ProjectionAdministrationService(
            ProjectionJobStatusStore statusStore,
            Clock clock,
            List<ProjectionExecutor> executors
    ) {
        this.statusStore = Objects.requireNonNull(statusStore, "statusStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.configuredTypes = List.copyOf(
                Objects.requireNonNull(executors, "executors must not be null")
        ).stream()
                .map(ProjectionExecutor::projectionType)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 列出一个租户文档已脱敏的投影状态。
     */
    public List<ProjectionJobResponse> find(
            PrincipalContext principal,
            UUID documentId
    ) {
        requireAdmin(principal);
        return statusStore.findByDocument(
                principal.tenantId(),
                new DocumentId(documentId)
        ).stream().map(state -> new ProjectionJobResponse(
                state.id(),
                state.projectionType().name(),
                state.status(),
                state.attemptCount(),
                state.lastErrorCode(),
                state.availableAt(),
                state.updatedAt()
        )).toList();
    }

    /**
     * 若存在则重新入队一条死信投影。
     */
    public boolean retry(
            PrincipalContext principal,
            UUID documentId,
            String projectionType
    ) {
        requireAdmin(principal);
        ProjectionType type;
        try {
            type = ProjectionType.valueOf(projectionType.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalidType) {
            throw new IllegalArgumentException("unsupported projection type", invalidType);
        }
        return statusStore.requeueDead(
                principal.tenantId(),
                new DocumentId(documentId),
                type,
                clock.instant()
        );
    }

    /**
     * 将一个空间的活动修订按本进程已配置的全部通道重新入队。
     */
    public RebuildResult rebuild(
            PrincipalContext principal,
            String spaceId
    ) {
        requireAdmin(principal);
        if (configuredTypes.isEmpty()) {
            return new RebuildResult(0, Set.of());
        }
        int jobs = statusStore.rebuildSpace(
                principal.tenantId(),
                new KnowledgeSpaceId(spaceId),
                configuredTypes,
                clock.instant()
        );
        return new RebuildResult(jobs, configuredTypes);
    }

    /**
     * 供 HTTP Adapter 使用的稳定重建结果。
     */
    public record RebuildResult(int jobs, Set<ProjectionType> projectionTypes) {
        public RebuildResult {
            if (jobs < 0) {
                throw new IllegalArgumentException("jobs must be non-negative");
            }
            projectionTypes = Set.copyOf(projectionTypes);
        }
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }
}
