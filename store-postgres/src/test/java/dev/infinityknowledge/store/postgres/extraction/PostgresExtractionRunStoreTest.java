package dev.infinityknowledge.store.postgres.extraction;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionPreview;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ChunkDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.CleaningDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemDiagnostics;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/** 验证抽取诊断 JSONB 固定形状可以无损往返。 */
class PostgresExtractionRunStoreTest {

    @Test
    void roundTripsTypedCleanAndChunkDetailsWithoutContentFields() {
        var store = new PostgresExtractionRunStore(
                mock(NamedParameterJdbcTemplate.class),
                mock(TransactionTemplate.class)
        );
        ItemDiagnostics diagnostics = new ItemDiagnostics(
                "markdown-structure",
                "pipeline-v6:test",
                3,
                2,
                1,
                2,
                3,
                new CleaningDiagnostics(2, 1, Map.of("FOOTER_METADATA_ONLY", 1)),
                new ChunkDiagnostics(
                        1, 1, 3, 1, 1, 1,
                        1, 1, 0, 1, 0, 0,
                        2, 10, 15.0D, 20
                )
        );

        String json = store.diagnosticsJson(diagnostics);
        var restored = store.diagnosticDetails(json);

        assertEquals(diagnostics.cleaning(), restored.cleaning());
        assertEquals(diagnostics.chunking(), restored.chunking());
        assertFalse(json.contains("content"));
        assertFalse(json.contains("vector"));
        assertFalse(json.contains("response"));
    }

    @Test
    void roundTripsConfigPreviewAndGateUsingFixedJsonShapes() {
        var store = new PostgresExtractionRunStore(
                mock(NamedParameterJdbcTemplate.class),
                mock(TransactionTemplate.class)
        );
        ExtractionConfigSnapshot config = snapshot();
        UUID artifactId = UUID.randomUUID();
        UUID elementId = UUID.randomUUID();
        var range = new ExtractionPreview.ArtifactRange(artifactId, 0, 5, 1);
        var preview = new ExtractionPreview(
                artifactId,
                5,
                1,
                1,
                false,
                List.of(new ExtractionPreview.Element(
                        elementId,
                        null,
                        ElementType.PARAGRAPH,
                        0,
                        List.of("标题"),
                        null,
                        "KEEP",
                        "KEPT",
                        range,
                        5,
                        "hello",
                        false
                )),
                List.of(new ExtractionPreview.Chunk(
                        UUID.randomUUID(),
                        0,
                        List.of("标题"),
                        5,
                        "hello",
                        false,
                        List.of(new ExtractionPreview.ElementRange(
                                elementId,
                                0,
                                5,
                                1,
                                range
                        ))
                ))
        );
        var gate = new ExtractionGateReport(
                ExtractionGateStatus.PASSED,
                "dataset-v1",
                "dataset-v1",
                config.fingerprint(),
                Instant.parse("2026-08-22T03:00:00Z"),
                List.of(new ExtractionGateReport.CaseResult(
                        "case-a",
                        "a".repeat(64),
                        true,
                        1.0D,
                        0,
                        0,
                        1.0D,
                        0,
                        List.of()
                )),
                null
        );

        assertEquals(config, store.configSnapshot(store.configSnapshotJson(config)));
        assertEquals(preview, store.preview(store.previewJson(preview)));
        assertEquals(gate, store.gateReport(store.gateReportJson(gate)));
    }

    private static ExtractionConfigSnapshot snapshot() {
        var keep = dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
                .CleaningAction.KEEP;
        var remove = dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
                .CleaningAction.REMOVE;
        return new ExtractionConfigSnapshot(
                DocumentProcessingContract.create(
                        "pipeline-v6",
                        "normalizer-schema-v1",
                        Map.of("text/markdown", "markdown-structure/v1"),
                        "cleaner-v1",
                        "chunker-v1"
                ),
                "identity-bytes-v1",
                Map.of("text/markdown", "markdown-structure"),
                new ExtractionConfigSnapshot.Cleaning(keep, keep, keep, keep, remove),
                new ExtractionConfigSnapshot.Chunker(
                        "STRUCTURAL", "UTF8_BYTE_BUDGET", 64, 128, 256, 16, "{}"
                ),
                "0".repeat(64)
        );
    }
}
