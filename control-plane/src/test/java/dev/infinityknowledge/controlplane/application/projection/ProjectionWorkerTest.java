package dev.infinityknowledge.controlplane.application.projection;

import dev.infinityknowledge.controlplane.config.IndexingProperties;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.indexing.ProjectionJob;
import dev.infinityknowledge.spi.indexing.ProjectionJobQueue;
import dev.infinityknowledge.spi.indexing.ProjectionExecutor;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionSourceStore;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");

    @Test
    void schedulesRetryWithExponentialBackoff() {
        RecordingQueue queue = new RecordingQueue(job(2));
        ProjectionWorker worker = worker(queue);

        worker.poll();

        assertThat(queue.failed).isTrue();
        assertThat(queue.dead).isFalse();
        assertThat(queue.availableAt).isEqualTo(NOW.plusSeconds(4));
        assertThat(queue.errorCode).isEqualTo("VECTOR_PROJECTION_FAILED");
    }

    @Test
    void movesExhaustedJobToDeadLetterState() {
        RecordingQueue queue = new RecordingQueue(job(5));
        ProjectionWorker worker = worker(queue);

        worker.poll();

        assertThat(queue.failed).isTrue();
        assertThat(queue.dead).isTrue();
        assertThat(queue.availableAt).isEqualTo(NOW);
    }

    @Test
    void completesSupersededJobWithoutLoadingOrPublishingIt() {
        RecordingQueue queue = new RecordingQueue(job(1));
        ProjectionWorker worker = worker(queue, false);

        worker.poll();

        assertThat(queue.completed).isTrue();
        assertThat(queue.failed).isFalse();
    }

    private static ProjectionWorker worker(RecordingQueue queue) {
        return worker(queue, true);
    }

    private static ProjectionWorker worker(RecordingQueue queue, boolean active) {
        ProjectionSourceStore sourceStore = job -> {
            throw new IllegalStateException("simulated dependency outage");
        };
        return new ProjectionWorker(
                queue,
                sourceStore,
                new ActiveRevisionGuard() {
                    @Override
                    public boolean isActive(
                            TenantId tenantId,
                            DocumentId documentId,
                            UUID revisionId
                    ) {
                        return active;
                    }

                    @Override
                    public List<RetrievalCandidate> retainActive(
                            TenantId tenantId,
                            List<RetrievalCandidate> candidates
                    ) {
                        return candidates;
                    }
                },
                List.of(new ProjectionExecutor() {
                    @Override
                    public ProjectionType projectionType() {
                        return ProjectionType.VECTOR;
                    }

                    @Override
                    public void project(ProjectionSource source) {
                    }
                }),
                new IndexingProperties(
                        8,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(2),
                        5,
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(5)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static ProjectionJob job(int attempt) {
        return new ProjectionJob(
                UUID.randomUUID(),
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                DocumentId.random(),
                UUID.randomUUID(),
                ProjectionType.VECTOR,
                attempt,
                7L
        );
    }

    private static final class RecordingQueue implements ProjectionJobQueue {

        private final ProjectionJob job;
        private String workerId;
        private boolean failed;
        private boolean completed;
        private boolean dead;
        private String errorCode;
        private Instant availableAt;

        private RecordingQueue(ProjectionJob job) {
            this.job = job;
        }

        @Override
        public List<ProjectionJob> claim(
                String claimedBy,
                Set<ProjectionType> supportedTypes,
                int limit,
                Duration leaseDuration,
                Instant now
        ) {
            workerId = claimedBy;
            return List.of(job);
        }

        @Override
        public boolean complete(
                UUID jobId,
                String completedBy,
                long leaseToken,
                Instant now
        ) {
            assertThat(completedBy).isEqualTo(workerId);
            assertThat(leaseToken).isEqualTo(job.leaseToken());
            completed = true;
            return true;
        }

        @Override
        public boolean heartbeat(
                UUID jobId,
                String heartbeatBy,
                long leaseToken,
                Instant leaseUntil,
                Instant now
        ) {
            assertThat(heartbeatBy).isEqualTo(workerId);
            assertThat(leaseToken).isEqualTo(job.leaseToken());
            return true;
        }

        @Override
        public boolean fail(
                UUID jobId,
                String failedBy,
                long leaseToken,
                String stableErrorCode,
                Instant retryAt,
                boolean deadLetter,
                Instant now
        ) {
            assertThat(failedBy).isEqualTo(workerId);
            assertThat(leaseToken).isEqualTo(job.leaseToken());
            failed = true;
            dead = deadLetter;
            errorCode = stableErrorCode;
            availableAt = retryAt;
            return true;
        }
    }
}
