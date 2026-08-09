package dev.infinityknowledge.controlplane.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Non-sensitive operational projection status returned to tenant administrators.
 */
public record ProjectionJobResponse(
        UUID id,
        String projectionType,
        String status,
        int attemptCount,
        String lastErrorCode,
        Instant availableAt,
        Instant updatedAt
) {
}
