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
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.spi.indexing.ProjectionStatus;
import dev.infinityknowledge.spi.indexing.ProjectionType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedKeywordProjectionExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-16T08:00:00Z");
    private static final UUID GENERATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final IndexingContract INDEXING_CONTRACT = new IndexingContract(
            "normalizer-v1-0123456789abcdef0123456789abcdef0123456789abcdef",
            "chunker-v1-0123456789abcdef0123456789abcdef0123456789abcdef"
    );
    private static final IndexPhysicalContract PHYSICAL_CONTRACT =
            IndexPhysicalContract.withKeywordTarget("v1", "a".repeat(64));

    @Test
    void resolvesSpaceContractAndRecordsKeywordProjection() {
        Fixture fixture = fixture();
        RecordingProjectionStore store = new RecordingProjectionStore();
        AtomicBoolean upserted = new AtomicBoolean();
        TrackedKeywordProjectionExecutor executor = new TrackedKeywordProjectionExecutor(
                source -> upserted.set(true),
                store,
                new EmbeddingSpec("zhipu", "embedding-3", 2_048),
                PHYSICAL_CONTRACT,
                (tenantId, spaceId) -> {
                    assertThat(tenantId).isEqualTo(fixture.document().tenantId());
                    assertThat(spaceId).isEqualTo(fixture.document().spaceId());
                    return INDEXING_CONTRACT;
                },
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        executor.project(new ProjectionSource(
                fixture.document(),
                List.of(fixture.chunk())
        ));

        assertThat(upserted).isTrue();
        assertThat(store.statuses)
                .containsExactly(ProjectionStatus.PENDING, ProjectionStatus.SUCCEEDED);
        assertThat(store.normalizerVersion).isEqualTo(INDEXING_CONTRACT.normalizerVersion());
        assertThat(store.chunkerVersion).isEqualTo(INDEXING_CONTRACT.chunkerVersion());
        assertThat(store.physicalContract).isEqualTo(PHYSICAL_CONTRACT);
    }

    private static Fixture fixture() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentId documentId =
                new DocumentId(UUID.fromString("20000000-0000-0000-0000-000000000002"));
        UUID revisionId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                tenantId,
                spaceId,
                "Index generation runbook",
                new SourceDescriptor(
                        "api",
                        SourceType.UPLOAD,
                        "index-generation",
                        "urn:test:index-generation",
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                80,
                Map.of(),
                NOW,
                NOW
        );
        UUID elementId = UUID.fromString("50000000-0000-0000-0000-000000000002");
        String content = "Resolve the space profile before selecting an index generation.";
        KnowledgeChunk chunk = new KnowledgeChunk(
                UUID.fromString("40000000-0000-0000-0000-000000000002"),
                tenantId,
                spaceId,
                documentId,
                revisionId,
                List.of(elementId),
                List.of(new ChunkSourceSpan(elementId, 0, content.length(), null)),
                0,
                List.of("Projection"),
                content,
                "Projection\n\n" + content,
                "abc124",
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
        public void recordProjectionStatus(
                TenantId tenantId,
                UUID generationId,
                DocumentId documentId,
                UUID revisionId,
                ProjectionType projectionType,
                ProjectionStatus status,
                Instant now
        ) {
            assertThat(projectionType).isEqualTo(ProjectionType.KEYWORD);
            statuses.add(status);
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
            throw new AssertionError("keyword projection must not use vector status method");
        }
    }
}
