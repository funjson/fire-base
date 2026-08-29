package dev.infinityknowledge.controlplane.application.projection;

import dev.infinityknowledge.controlplane.config.ingestion.IndexingContract;
import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.IndexProjectionStore;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.vector.VectorProjectionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackedVectorProjectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");
    private static final UUID GENERATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final IndexingContract INDEXING_CONTRACT = new IndexingContract(
            "normalizer-v1-0123456789abcdef0123456789abcdef0123456789abcdef",
            "chunker-v1-0123456789abcdef0123456789abcdef0123456789abcdef"
    );
    private static final IndexPhysicalContract PHYSICAL_CONTRACT =
            IndexPhysicalContract.withKeywordTarget("v1", "a".repeat(64));

    @Test
    void recordsPendingThenSucceeded() {
        RecordingProjectionStore store = new RecordingProjectionStore();
        TrackedVectorProjectionService service = service(store, (document, chunks) -> {
        });

        Fixture fixture = fixture();
        service.project(fixture.document(), List.of(fixture.chunk()));

        assertThat(store.statuses)
                .containsExactly(ProjectionStatus.PENDING, ProjectionStatus.SUCCEEDED);
        assertThat(store.normalizerVersion).isEqualTo(INDEXING_CONTRACT.normalizerVersion());
        assertThat(store.chunkerVersion).isEqualTo(INDEXING_CONTRACT.chunkerVersion());
        assertThat(store.physicalContract).isEqualTo(PHYSICAL_CONTRACT);
    }

    @Test
    void recordsFailedAndPreservesProjectionFailure() {
        RecordingProjectionStore store = new RecordingProjectionStore();
        IllegalStateException expected = new IllegalStateException("Milvus unavailable");
        TrackedVectorProjectionService service = service(store, (document, chunks) -> {
            throw expected;
        });

        Fixture fixture = fixture();

        assertThatThrownBy(() -> service.project(fixture.document(), List.of(fixture.chunk())))
                .isSameAs(expected);
        assertThat(store.statuses)
                .containsExactly(ProjectionStatus.PENDING, ProjectionStatus.FAILED);
    }

    private static TrackedVectorProjectionService service(
            RecordingProjectionStore store,
            VectorProjectionService delegate
    ) {
        return new TrackedVectorProjectionService(
                delegate,
                store,
                new EmbeddingSpec("zhipu", "embedding-3", 2048),
                PHYSICAL_CONTRACT,
                (tenantId, spaceId) -> INDEXING_CONTRACT,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static Fixture fixture() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentId documentId =
                new DocumentId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
        UUID revisionId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                tenantId,
                spaceId,
                "Redis pool runbook",
                new SourceDescriptor(
                        "api",
                        SourceType.UPLOAD,
                        "redis-pool",
                        "urn:test:redis-pool",
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                80,
                Map.of(),
                NOW,
                NOW
        );
        UUID elementId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        String content = "Increase the Redis connection pool only after confirming saturation.";
        KnowledgeChunk chunk = new KnowledgeChunk(
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                tenantId,
                spaceId,
                documentId,
                revisionId,
                List.of(elementId),
                List.of(new ChunkSourceSpan(elementId, 0, content.length(), null)),
                0,
                List.of("Diagnosis"),
                content,
                "Diagnosis\n\n" + content,
                "abc123",
                Map.of()
        );
        return new Fixture(document, chunk);
    }

    private record Fixture(KnowledgeDocument document, KnowledgeChunk chunk) {
    }

    private static final class RecordingProjectionStore implements IndexProjectionStore {

        private final List<ProjectionStatus> statuses = new ArrayList<>();
        private String normalizerVersion;
        private String chunkerVersion;
        private IndexPhysicalContract physicalContract;

        @Override
        public UUID resolveActiveGeneration(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                EmbeddingSpec embeddingSpec,
                IndexPhysicalContract physicalContract,
                String normalizerVersion,
                String chunkerVersion,
                Instant now
        ) {
            this.physicalContract = physicalContract;
            this.normalizerVersion = normalizerVersion;
            this.chunkerVersion = chunkerVersion;
            return GENERATION_ID;
        }

        @Override
        public void recordVectorStatus(
                TenantId tenantId,
                UUID generationId,
                DocumentId documentId,
                UUID revisionId,
                ProjectionStatus status,
                Instant now
        ) {
            statuses.add(status);
        }
    }
}
