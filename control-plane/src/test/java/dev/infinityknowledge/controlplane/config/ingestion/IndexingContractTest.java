package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证索引代际只从 Space 创建时固化的实际处理合同派生。 */
class IndexingContractTest {

    @Test
    void usesTheWholeFrozenParserContractMap() {
        IndexingContract markdownOnly = IndexingContract.fromProcessingContract(
                contract(
                        Map.of("text/markdown", "markdown-v1"),
                        "cleaner-v1",
                        "chunker-v1"
                )
        );
        IndexingContract allFormats = IndexingContract.fromProcessingContract(
                contract(
                        Map.of(
                                "text/html", "html-v1",
                                "text/markdown", "markdown-v1",
                                "text/plain", "plain-v1"
                        ),
                        "cleaner-v1",
                        "chunker-v1"
                )
        );

        assertThat(allFormats.normalizerVersion()).startsWith("normalizer-v2-");
        assertThat(allFormats.normalizerVersion()).hasSizeLessThanOrEqualTo(64);
        assertThat(allFormats.normalizerVersion())
                .isNotEqualTo(markdownOnly.normalizerVersion());
        assertThat(allFormats.chunkerVersion()).startsWith("chunker-v2-");
    }

    @Test
    void componentChangesAffectOnlyTheExpectedGenerationSide() {
        IndexingContract baseline = IndexingContract.fromProcessingContract(
                contract(Map.of("application/pdf", "docling-v1"), "keep", "compact")
        );
        IndexingContract parserChanged = IndexingContract.fromProcessingContract(
                contract(Map.of("application/pdf", "pdfbox-v1"), "keep", "compact")
        );
        IndexingContract cleanerChanged = IndexingContract.fromProcessingContract(
                contract(Map.of("application/pdf", "docling-v1"), "remove", "compact")
        );
        IndexingContract chunkerChanged = IndexingContract.fromProcessingContract(
                contract(Map.of("application/pdf", "docling-v1"), "keep", "wide")
        );

        assertThat(parserChanged.normalizerVersion())
                .isNotEqualTo(baseline.normalizerVersion());
        assertThat(cleanerChanged.normalizerVersion())
                .isNotEqualTo(baseline.normalizerVersion());
        assertThat(chunkerChanged.normalizerVersion())
                .isEqualTo(baseline.normalizerVersion());
        assertThat(parserChanged.chunkerVersion())
                .isEqualTo(baseline.chunkerVersion());
        assertThat(chunkerChanged.chunkerVersion())
                .isNotEqualTo(baseline.chunkerVersion());
    }

    @Test
    void equivalentFrozenContractsShareGenerationContract() {
        var contract = contract(
                Map.of("application/pdf", "docling-v1"),
                "cleaner-v1",
                "semantic-model-a"
        );

        assertThat(IndexingContract.fromProcessingContract(contract))
                .isEqualTo(IndexingContract.fromProcessingContract(contract));
    }

    private static DocumentProcessingContract contract(
            Map<String, String> parsers,
            String cleaner,
            String chunker
    ) {
        return DocumentProcessingContract.create(
                "pipeline-v7",
                "normalizer-schema-v2",
                parsers,
                cleaner,
                chunker
        );
    }
}
