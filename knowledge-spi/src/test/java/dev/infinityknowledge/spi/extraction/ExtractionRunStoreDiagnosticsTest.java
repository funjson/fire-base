package dev.infinityknowledge.spi.extraction;

import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ChunkDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.CleaningDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemDiagnostics;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证抽取诊断固定字段、原因码快照和跨层计数一致性。 */
class ExtractionRunStoreDiagnosticsTest {

    @Test
    void keepsAStableDefensiveCleaningReasonSnapshot() {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("WATERMARK_REMOVED", 2);
        CleaningDiagnostics diagnostics = new CleaningDiagnostics(3, 1, reasons);

        reasons.put("HEADER_REMOVED", 1);

        assertEquals(Map.of("WATERMARK_REMOVED", 2), diagnostics.reasonCodeCounts());
        assertThrows(
                UnsupportedOperationException.class,
                () -> diagnostics.reasonCodeCounts().put("FOOTER_REMOVED", 1)
        );
    }

    @Test
    void rejectsChunkCountThatDoesNotMatchTheFixedChunkDiagnostics() {
        ChunkDiagnostics chunking = new ChunkDiagnostics(
                1, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                1, 10, 10.0D, 10
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ItemDiagnostics(
                        "markdown-structure",
                        "pipeline-v6:test",
                        1,
                        2,
                        1,
                        1,
                        1,
                        new CleaningDiagnostics(1, 0, Map.of()),
                        chunking
                )
        );
    }
}
