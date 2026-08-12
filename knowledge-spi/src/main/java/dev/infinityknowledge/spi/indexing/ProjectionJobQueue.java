package dev.infinityknowledge.spi.indexing;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Durable lease-based queue for at-least-once external index projections.
 */
public interface ProjectionJobQueue {

    /**
     * Atomically claims available work using a bounded lease.
     */
    default List<ProjectionJob> claim(
            String workerId,
            int limit,
            Duration leaseDuration,
            Instant now
    ) {
        return claim(
                workerId,
                EnumSet.allOf(ProjectionType.class),
                limit,
                leaseDuration,
                now
        );
    }

    /**
     * Claims only jobs supported by the current worker process.
     */
    List<ProjectionJob> claim(
            String workerId,
            Set<ProjectionType> supportedTypes,
            int limit,
            Duration leaseDuration,
            Instant now
    );

    /**
     * Marks a leased job successful, or atomically returns it to pending when
     * the source changed while it was running.
     *
     * @return {@code false} when the lease expired or its fencing token is stale
     */
    boolean complete(UUID jobId, String workerId, long leaseToken, Instant now);

    /**
     * Extends a live lease before an external projection starts.
     *
     * @return {@code false} when the lease expired or its fencing token is stale
     */
    boolean heartbeat(
            UUID jobId,
            String workerId,
            long leaseToken,
            Instant leaseUntil,
            Instant now
    );

    /**
     * Releases a leased job for retry or moves it to the dead-letter state.
     *
     * @return {@code false} when the lease expired or its fencing token is stale
     */
    boolean fail(
            UUID jobId,
            String workerId,
            long leaseToken,
            String errorCode,
            Instant availableAt,
            boolean dead,
            Instant now
    );
}
