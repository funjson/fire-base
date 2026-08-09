package dev.infinityknowledge.spi.indexing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sanitized operational view of one external projection job.
 *
 * @param id job identifier
 * @param projectionType projection channel
 * @param status lifecycle state
 * @param attemptCount delivery count
 * @param lastErrorCode stable non-sensitive failure code
 * @param availableAt next eligible attempt
 * @param updatedAt last transition time
 */
public record ProjectionJobState(
        UUID id,
        ProjectionType projectionType,
        String status,
        int attemptCount,
        String lastErrorCode,
        Instant availableAt,
        Instant updatedAt
) {

    /**
     * Validates the operational view.
     */
    public ProjectionJobState {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectionType, "projectionType must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must be non-negative");
        }
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
