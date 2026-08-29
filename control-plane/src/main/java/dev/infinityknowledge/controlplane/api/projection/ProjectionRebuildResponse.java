package dev.infinityknowledge.controlplane.api.projection;

import dev.infinityknowledge.controlplane.application.projection.ProjectionAdministrationService;

import java.util.List;

/**
 * Result of requeuing all active revisions in one knowledge space.
 */
public record ProjectionRebuildResponse(
        int jobs,
        List<String> projectionTypes
) {
    static ProjectionRebuildResponse from(
            ProjectionAdministrationService.RebuildResult result
    ) {
        return new ProjectionRebuildResponse(
                result.jobs(),
                result.projectionTypes().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList()
        );
    }
}
