package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.config.IndexingProperties;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionJob;
import dev.infinityknowledge.spi.indexing.ProjectionJobQueue;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionSourceStore;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lease-based at-least-once worker for external index projections.
 */
public final class ProjectionWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            ProjectionWorker.class
    );

    private final ProjectionJobQueue queue;
    private final ProjectionSourceStore sourceStore;
    private final ActiveRevisionGuard activeRevisionGuard;
    private final Map<ProjectionType, ProjectionExecutor> executors;
    private final IndexingProperties properties;
    private final Clock clock;
    private final String workerId;

    /**
     * Creates a uniquely identified process-local worker.
     */
    public ProjectionWorker(
            ProjectionJobQueue queue,
            ProjectionSourceStore sourceStore,
            ActiveRevisionGuard activeRevisionGuard,
            List<ProjectionExecutor> executors,
            IndexingProperties properties,
            Clock clock
    ) {
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.sourceStore = Objects.requireNonNull(sourceStore, "sourceStore must not be null");
        this.activeRevisionGuard = Objects.requireNonNull(
                activeRevisionGuard,
                "activeRevisionGuard must not be null"
        );
        this.executors = List.copyOf(
                Objects.requireNonNull(executors, "executors must not be null")
        ).stream().collect(Collectors.toUnmodifiableMap(
                ProjectionExecutor::projectionType,
                Function.identity()
        ));
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.workerId = "projection-" + UUID.randomUUID();
    }

    /**
     * Claims and processes one bounded batch. Overlapping nodes are safe because claims use leases.
     */
    @Scheduled(
            fixedDelayString = "${infinity.knowledge.indexing.poll-interval:1s}",
            initialDelayString = "${infinity.knowledge.indexing.poll-interval:1s}"
    )
    public void poll() {
        Instant now = clock.instant();
        if (executors.isEmpty()) {
            return;
        }
        List<ProjectionJob> jobs = queue.claim(
                workerId,
                executors.keySet(),
                properties.batchSize(),
                properties.leaseDuration(),
                now
        );
        if (!jobs.isEmpty()) {
            LOGGER.debug(
                    "Projection worker claimed jobs: workerId={}, count={}",
                    workerId,
                    jobs.size()
            );
        }
        jobs.forEach(this::process);
    }

    private void process(ProjectionJob job) {
        try {
            if (!activeRevisionGuard.isActive(
                    job.tenantId(),
                    job.documentId(),
                    job.revisionId()
            )) {
                queue.complete(job.id(), workerId, clock.instant());
                LOGGER.debug(
                        "Superseded projection skipped: jobId={}, tenantId={}, "
                                + "documentId={}, revisionId={}, type={}",
                        job.id(),
                        job.tenantId().value(),
                        job.documentId().value(),
                        job.revisionId(),
                        job.projectionType().name()
                );
                return;
            }
            var source = sourceStore.load(job);
            ProjectionExecutor executor = executors.get(job.projectionType());
            if (executor == null) {
                throw new IllegalStateException("worker received unsupported projection type");
            }
            executor.project(source);
            queue.complete(job.id(), workerId, clock.instant());
            LOGGER.debug(
                    "Projection completed: jobId={}, tenantId={}, documentId={}, type={}",
                    job.id(),
                    job.tenantId().value(),
                    job.documentId().value(),
                    job.projectionType().name()
            );
        } catch (RuntimeException projectionFailure) {
            Instant now = clock.instant();
            boolean dead = job.attempt() >= properties.maxAttempts();
            if (dead) {
                LOGGER.error(
                        "Projection failed: jobId={}, tenantId={}, documentId={}, type={}, "
                                + "attempt={}, deadLetter=true",
                        job.id(),
                        job.tenantId().value(),
                        job.documentId().value(),
                        job.projectionType().name(),
                        job.attempt(),
                        projectionFailure
                );
            } else {
                LOGGER.warn(
                        "Projection failed: jobId={}, tenantId={}, documentId={}, type={}, "
                                + "attempt={}, deadLetter=false",
                        job.id(),
                        job.tenantId().value(),
                        job.documentId().value(),
                        job.projectionType().name(),
                        job.attempt(),
                        projectionFailure
                );
            }
            queue.fail(
                    job.id(),
                    workerId,
                    job.projectionType().name() + "_PROJECTION_FAILED",
                    dead ? now : now.plus(backoff(job.attempt())),
                    dead,
                    now
            );
        }
    }

    private Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        Duration delay = properties.initialBackoff().multipliedBy(1L << exponent);
        return delay.compareTo(properties.maxBackoff()) > 0
                ? properties.maxBackoff()
                : delay;
    }
}
