package dev.infinityknowledge.spi.ingestion;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证空间文档处理配置跨适配器共享的不变量。 */
class SpaceDocumentProcessingConfigStoreTest {

    @Test
    void acceptsProviderTokenizerAndTokenSizing() {
        var configuration = new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                "SEMANTIC_REFINEMENT",
                "UTF8_BYTE_BUDGET",
                128,
                512,
                1_024,
                32,
                "{\"contextSlices\":1,\"embeddingProfileId\":\"default\","
                        + "\"mergeSimilarityThreshold\":0.85,"
                        + "\"splitSimilarityThreshold\":0.6}"
        );

        assertEquals("SEMANTIC_REFINEMENT", configuration.providerId());
        assertEquals("UTF8_BYTE_BUDGET", configuration.tokenizerId());
        assertEquals(128, configuration.minimumTokens());
        assertEquals(512, configuration.targetTokens());
        assertEquals(1_024, configuration.maximumTokens());
        assertEquals(32, configuration.overlapTokens());
    }

    @Test
    void rejectsInvalidCommonTokenSizing() {
        assertThrows(IllegalArgumentException.class, () -> configuration(0, 512, 1_024, 0));
        assertThrows(IllegalArgumentException.class, () -> configuration(512, 511, 1_024, 0));
        assertThrows(IllegalArgumentException.class, () -> configuration(128, 1_024, 512, 0));
        assertThrows(IllegalArgumentException.class, () -> configuration(128, 512, 1_024, 128));
    }

    @Test
    void rejectsUnstableProviderAndTokenizerIdentifiers() {
        assertThrows(IllegalArgumentException.class, () ->
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                        "semantic refinement",
                        "UTF8_BYTE_BUDGET",
                        128,
                        512,
                        1_024,
                        0,
                        "{}"
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                        "STRUCTURAL",
                        "java.lang.Token Counter",
                        128,
                        512,
                        1_024,
                        0,
                        "{}"
                )
        );
    }

    @Test
    void onlyAllowsRequestVersionZeroAndImmutableVersionOne() {
        assertEquals(0L, documentConfig(0L).version());
        assertEquals(1L, documentConfig(1L).version());
        assertEquals(contract(), documentConfig(1L).processingContract());
        assertThrows(IllegalArgumentException.class, () -> documentConfig(2L));
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            documentConfig(long version) {
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("engineering"),
                Map.of("text/plain", "plain-text"),
                SpaceDocumentProcessingConfigStore.CleaningConfiguration.defaults(),
                configuration(128, 512, 1_024, 32),
                version == 1L ? contract() : null,
                version,
                null,
                Instant.EPOCH
        );
    }

    private static DocumentProcessingContract contract() {
        return DocumentProcessingContract.create(
                "pipeline-v7",
                "normalizer-schema-v2",
                Map.of("text/plain", "plain-text-contract-v1"),
                "cleaner-v1",
                "chunker-v1"
        );
    }

    private static SpaceDocumentProcessingConfigStore.ChunkerConfiguration configuration(
            int minimumTokens,
            int targetTokens,
            int maximumTokens,
            int overlapTokens
    ) {
        return new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                "STRUCTURAL",
                "UTF8_BYTE_BUDGET",
                minimumTokens,
                targetTokens,
                maximumTokens,
                overlapTokens,
                "{}"
        );
    }
}
