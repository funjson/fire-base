package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 验证配置指纹只绑定有效处理语义，并能无损恢复 Worker 配置。 */
class ExtractionConfigSnapshotsTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");

    @Test
    void requestAndPersistedStateShareFingerprintButConfigurationChangesIt() {
        var first = snapshot(config(0L, 256));
        var persisted = snapshot(config(1L, 256));
        var changed = snapshot(config(1L, 512));

        assertEquals(first.fingerprint(), persisted.fingerprint());
        assertNotEquals(first.fingerprint(), changed.fingerprint());
    }

    @Test
    void restoresEveryEffectiveFieldAndKeepsExpectedVersionForWriteFence() {
        var source = config(1L, 512);
        var snapshot = snapshot(source);

        var restored = ExtractionConfigSnapshots.restore(TENANT, SPACE, 1L, snapshot);

        assertEquals(source.parserSelections(), restored.parserSelections());
        assertEquals(source.cleaning(), restored.cleaning());
        assertEquals(source.chunker(), restored.chunker());
        assertEquals(source.processingContract(), restored.processingContract());
        assertEquals(1L, restored.version());
        assertEquals(snapshot.fingerprint(), snapshot(restored).fingerprint());
    }

    private static SpaceDocumentProcessingConfig config(long version, int maximumTokens) {
        return new SpaceDocumentProcessingConfig(
                TENANT,
                SPACE,
                Map.of("text/markdown", "markdown-structure"),
                CleaningConfiguration.defaults(),
                new ChunkerConfiguration(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        64,
                        128,
                        maximumTokens,
                        16,
                        "{}"
                ),
                contract(maximumTokens),
                version,
                null,
                Instant.parse("2026-08-22T00:00:00Z")
        );
    }

    private static ExtractionConfigSnapshot snapshot(
            SpaceDocumentProcessingConfig config
    ) {
        return ExtractionConfigSnapshots.capture(
                config,
                config.processingContract(),
                DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
        );
    }

    private static DocumentProcessingContract contract(int maximumTokens) {
        return DocumentProcessingContract.create(
                "pipeline-v6",
                "normalizer-schema-v2",
                Map.of("text/markdown", "markdown-parser-v1"),
                "cleaner-v1",
                "chunker-max-" + maximumTokens
        );
    }
}
