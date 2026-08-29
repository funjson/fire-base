package dev.infinityknowledge.spi.ingestion;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证实际处理合同的规范化、可解释材料与总指纹不变量。 */
class DocumentProcessingContractTest {

    @Test
    void fingerprintIsStableAcrossParserMapOrder() {
        var first = contract(new LinkedHashMap<>(Map.of(
                "text/plain", "plain-v1",
                "text/markdown", "markdown-v1"
        )));
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("text/plain", "plain-v1");
        reversed.put("text/markdown", "markdown-v1");

        assertEquals(first, contract(reversed));
        assertEquals(
                java.util.List.of("text/markdown", "text/plain"),
                first.parserContracts().keySet().stream().toList()
        );
    }

    @Test
    void everyActualComponentChangesTheTotalFingerprint() {
        var baseline = contract(Map.of("text/plain", "plain-v1"));

        assertAll(
                () -> assertNotEquals(
                        baseline.fingerprint(),
                        contract(
                                "pipeline-v8",
                                "normalizer-schema-v2",
                                Map.of("text/plain", "plain-v1"),
                                "cleaner-v1",
                                "chunker-v1"
                        ).fingerprint()
                ),
                () -> assertNotEquals(
                        baseline.fingerprint(),
                        contract(
                                "pipeline-v7",
                                "normalizer-schema-v3",
                                Map.of("text/plain", "plain-v1"),
                                "cleaner-v1",
                                "chunker-v1"
                        ).fingerprint()
                ),
                () -> assertNotEquals(
                        baseline.fingerprint(),
                        contract(
                                "pipeline-v7",
                                "normalizer-schema-v2",
                                Map.of("text/plain", "plain-v2"),
                                "cleaner-v1",
                                "chunker-v1"
                        ).fingerprint()
                ),
                () -> assertNotEquals(
                        baseline.fingerprint(),
                        contract(
                                "pipeline-v7",
                                "normalizer-schema-v2",
                                Map.of("text/plain", "plain-v1"),
                                "cleaner-v2",
                                "chunker-v1"
                        ).fingerprint()
                ),
                () -> assertNotEquals(
                        baseline.fingerprint(),
                        contract(
                                "pipeline-v7",
                                "normalizer-schema-v2",
                                Map.of("text/plain", "plain-v1"),
                                "cleaner-v1",
                                "chunker-v2"
                        ).fingerprint()
                )
        );
    }

    @Test
    void rejectsStoredFingerprintThatDoesNotMatchComponents() {
        var valid = contract(Map.of("text/plain", "plain-v1"));

        assertThrows(IllegalArgumentException.class, () ->
                new DocumentProcessingContract(
                        valid.pipelineContract(),
                        valid.normalizerSchemaContract(),
                        valid.parserContracts(),
                        valid.cleanerContract(),
                        valid.chunkerContract(),
                        "0".repeat(64)
                )
        );
    }

    private static DocumentProcessingContract contract(
            Map<String, String> parsers
    ) {
        return contract(
                "pipeline-v7",
                "normalizer-schema-v2",
                parsers,
                "cleaner-v1",
                "chunker-v1"
        );
    }

    private static DocumentProcessingContract contract(
            String pipeline,
            String normalizer,
            Map<String, String> parsers,
            String cleaner,
            String chunker
    ) {
        return DocumentProcessingContract.create(
                pipeline,
                normalizer,
                parsers,
                cleaner,
                chunker
        );
    }
}
