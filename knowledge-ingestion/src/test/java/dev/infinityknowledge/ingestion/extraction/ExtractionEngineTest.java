package dev.infinityknowledge.ingestion.extraction;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningAction;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证统一抽取内核的阶段结果、身份稳定性和完整性边界。 */
class ExtractionEngineTest {
    private static final TenantId TENANT_ID = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE_ID = new KnowledgeSpaceId("engineering");
    private static final DocumentId DOCUMENT_ID = new DocumentId(UUID.fromString(
            "2f1393ce-c233-4aa0-b1a5-8eaacccf1d91"
    ));

    @Test
    void executesParseCleanAndChunkWithStageDiagnostics() {
        byte[] source = sourceBytes();
        long[] ticks = {100L, 110L, 200L, 230L, 300L, 370L};
        AtomicInteger tickIndex = new AtomicInteger();
        LongSupplier ticker = () -> ticks[tickIndex.getAndIncrement()];
        ExtractionResult result = engine(ticker).extract(request(source, config(1L)));

        assertThat(result.mediaType()).isEqualTo("text/markdown");
        assertThat(result.contracts().pipeline()).isEqualTo("pipeline-v7");
        assertThat(result.contracts().processorVersion()).isEqualTo(
                "pipeline-v7:74d87debd9b2fa09ff207bbf0937fd16a934f074b3e8a4ac"
        );
        assertThat(result.sourceSha256()).isEqualTo(IngestionIdentity.sha256(source));
        assertThat(result.artifact().contract()).isEqualTo("utf8-line-endings-lf-v1");
        assertThat(result.elementProvenance()).hasSize(3);
        assertThat(result.cleaningDecisions()).hasSize(3);
        assertThat(result.diagnostics().parse().parserId())
                .isEqualTo("markdown-structure");
        assertThat(result.diagnostics().parse().producedElements()).isEqualTo(3);
        assertThat(result.diagnostics().cleaning().indexableElements()).isEqualTo(2);
        assertThat(result.diagnostics().cleaning().metadataOnlyElements()).isEqualTo(1);
        assertThat(result.diagnostics().cleaning().reasonCodeCounts())
                .containsEntry("FRONT_MATTER_METADATA_ONLY", 1);
        assertThat(result.retainedElements())
                .extracting(element -> element.ordinal())
                .containsExactly(0, 1, 2);
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.diagnostics().parse().duration().toNanos()).isEqualTo(10L);
        assertThat(result.diagnostics().cleaning().duration().toNanos()).isEqualTo(30L);
        assertThat(result.diagnostics().chunking().duration().toNanos()).isEqualTo(70L);
        assertThat(result.diagnostics().chunking().diagnostics().finalChunkCount())
                .isEqualTo(result.chunks().size());
    }

    @Test
    void requestAndPersistedStateDoNotChangeRevisionIdentity() {
        byte[] source = sourceBytes();

        ExtractionResult first = engine().extract(request(source, config(1L)));
        ExtractionResult second = engine().extract(request(source, config(0L)));

        assertThat(second.revisionId()).isEqualTo(first.revisionId());
        assertThat(second.contracts()).isEqualTo(first.contracts());
    }

    @Test
    void removed_element_keeps_non_content_decision_and_provenance() {
        byte[] source = sourceBytes();
        ExtractionResult result = engine().extract(request(
                source,
                config(1L, CleaningAction.REMOVE)
        ));

        assertThat(result.retainedElements()).hasSize(2);
        assertThat(result.elementProvenance()).hasSize(3);
        assertThat(result.cleaningDecisions()).first().satisfies(decision -> {
            assertThat(decision.action()).isEqualTo(
                    dev.infinityknowledge.ingestion.cleaning.DocumentCleaningConfiguration.Action.REMOVE
            );
            assertThat(decision.reasonCode()).isEqualTo("FRONT_MATTER_REMOVED");
            assertThat(result.elementProvenance())
                    .anyMatch(sourceRange -> sourceRange.elementId().equals(decision.elementId()));
        });
    }

    @Test
    void rejectsMismatchedContentBeforeParsing() {
        byte[] source = sourceBytes();
        ExtractionRequest invalid = new ExtractionRequest(
                TENANT_ID,
                SPACE_ID,
                DOCUMENT_ID,
                "text/markdown",
                "runbook.md",
                "zh-CN",
                DocumentProcessingContractFactory.MARKDOWN_NORMALIZER_CONTRACT,
                source,
                "0".repeat(64),
                DocumentParseLimits.defaults(),
                config(1L)
        );

        assertThatThrownBy(() -> engine().extract(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("contentHash does not match the supplied source bytes");
    }

    private static ExtractionEngine engine() {
        return engine(System::nanoTime);
    }

    private static ExtractionEngine engine(LongSupplier ticker) {
        return new ExtractionEngine(
                DocumentParserRegistry.standard(),
                new KnowledgeChunkerFactory(List.of(), List.of()),
                new DeterministicDocumentCleaningPolicy(),
                ticker
        );
    }

    private static ExtractionRequest request(
            byte[] source,
            SpaceDocumentProcessingConfig config
    ) {
        return new ExtractionRequest(
                TENANT_ID,
                SPACE_ID,
                DOCUMENT_ID,
                "text/markdown",
                "runbook.md",
                "zh-CN",
                DocumentProcessingContractFactory.MARKDOWN_NORMALIZER_CONTRACT,
                source,
                IngestionIdentity.sha256(source),
                DocumentParseLimits.defaults(),
                config
        );
    }

    private static SpaceDocumentProcessingConfig config(long version) {
        return config(version, CleaningAction.METADATA_ONLY);
    }

    private static SpaceDocumentProcessingConfig config(
            long version,
            CleaningAction frontMatter
    ) {
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        var request = new SpaceDocumentProcessingConfig(
                TENANT_ID,
                SPACE_ID,
                parsers.defaultParserSelections(),
                new CleaningConfiguration(
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        frontMatter
                ),
                new ChunkerConfiguration(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        64,
                        256,
                        512,
                        32,
                        "{}"
                ),
                0L,
                Instant.EPOCH
        );
        return new SpaceDocumentProcessingConfig(
                request.tenantId(),
                request.spaceId(),
                request.parserSelections(),
                request.cleaning(),
                request.chunker(),
                engine().processingContract(request),
                version,
                null,
                Instant.EPOCH
        );
    }

    private static byte[] sourceBytes() {
        return """
                ---
                owner: platform
                ---
                # Runbook

                检查连接池。
                """.getBytes(StandardCharsets.UTF_8);
    }
}
