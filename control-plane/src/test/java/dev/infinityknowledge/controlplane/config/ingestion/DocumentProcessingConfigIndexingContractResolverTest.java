package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingBudget;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentProcessingConfigIndexingContractResolverTest {

    @Test
    void resolvesSemanticContractWithoutCallingEmbeddingModel() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentParserRegistry parserRegistry = DocumentParserRegistry.standard();
        AtomicInteger modelCalls = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            KnowledgeChunkerFactory chunkerFactory = new KnowledgeChunkerFactory(
                    (texts, spec) -> {
                        modelCalls.incrementAndGet();
                        throw new AssertionError("contract resolution must not call model");
                    },
                    new EmbeddingSpec("test", "semantic-boundary", 3),
                    SemanticChunkingBudget.defaults(),
                    executor,
                    java.util.List.of(),
                    java.util.List.of()
            );
            var request = new SpaceDocumentProcessingConfig(
                    tenantId,
                    spaceId,
                    parserRegistry.defaultParserSelections(),
                    dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
                            .CleaningConfiguration.defaults(),
                    new ChunkerConfiguration(
                            "SEMANTIC_REFINEMENT",
                            "UTF8_BYTE_BUDGET",
                            128,
                            512,
                            1_024,
                            32,
                            dev.infinityknowledge.controlplane.application.ingestion
                                    .ChunkerProviderConfigurationJson.encode(
                                            "SEMANTIC_REFINEMENT",
                                            Map.of(
                                                    "contextSlices", 1,
                                                    "embeddingProfileId",
                                                    "test/semantic-boundary@3",
                                                    "mergeSimilarityThreshold", 0.85D,
                                                    "splitSimilarityThreshold", 0.60D
                                            )
                                    )
                    ),
                    0L,
                    Instant.parse("2026-08-16T00:00:00Z")
            );
            var config = new SpaceDocumentProcessingConfig(
                    request.tenantId(),
                    request.spaceId(),
                    request.parserSelections(),
                    request.cleaning(),
                    request.chunker(),
                    new DocumentProcessingContractFactory(
                            parserRegistry,
                            chunkerFactory,
                            new DeterministicDocumentCleaningPolicy()
                    ).create(request),
                    1L,
                    null,
                    Instant.parse("2026-08-16T00:00:00Z")
            );
            DocumentProcessingConfigIndexingContractResolver resolver =
                    new DocumentProcessingConfigIndexingContractResolver(
                            (requestedTenant, requestedSpace) -> config
                    );

            IndexingContract contract = resolver.resolve(tenantId, spaceId);

            assertThat(contract.normalizerVersion()).startsWith("normalizer-v2-");
            assertThat(contract.chunkerVersion()).startsWith("chunker-v2-");
            assertThat(modelCalls).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
    }
}
