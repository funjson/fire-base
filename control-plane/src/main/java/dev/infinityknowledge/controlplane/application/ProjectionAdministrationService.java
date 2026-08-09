package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.ProjectionJobResponse;
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
 * Tenant-scoped inspection and explicit dead-letter retry operations.
 */
@Service
public class ProjectionAdministrationService {

    private final ProjectionJobStatusStore statusStore;
    private final Clock clock;
    private final Set<ProjectionType> configuredTypes;

    /**
     * Creates the administration service.
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
     * Lists sanitized projection state for one tenant document.
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
     * Requeues one dead-lettered projection if it exists.
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
     * Requeues active revisions in one space for every channel configured in this process.
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
     * Stable rebuild result used by the HTTP adapter.
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
