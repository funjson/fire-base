package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningResult;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.ingestion.parser.ParsedDocument;
import dev.infinityknowledge.runtime.ingestion.DocumentPublicationService;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningAction;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证统一摄取主线的完整性校验和处理契约指纹。 */
class DocumentIngestionPipelineTest {

    @Test
    void rejectsContentHashThatDoesNotMatchSourceBytes() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        var pipeline = pipeline(catalog, writer);

        assertThrows(
                IllegalArgumentException.class,
                () -> pipeline.publish(command("newline-lf-v1", "0".repeat(64)))
        );

        verify(writer, never()).write(any());
    }

    @Test
    void normalizationContractParticipatesInRevisionIdentity() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        when(catalog.findDocumentId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(writer.write(any())).thenAnswer(invocation -> {
            KnowledgeWriteBatch batch = invocation.getArgument(0);
            return new KnowledgeWriteResult(
                    batch.document().id(),
                    batch.revision().id(),
                    true,
                    batch.chunks().size()
            );
        });
        var pipeline = pipeline(catalog, writer);
        String hash = IngestionIdentity.sha256(sourceBytes());

        var first = pipeline.publish(command("newline-lf-v1", hash));
        var second = pipeline.publish(command("identity-bytes-v1", hash));

        assertNotEquals(
                first.writeResult().revisionId(),
                second.writeResult().revisionId()
        );
    }

    @Test
    void resolvedConfigControlsChunkerContractAndWriteFenceVersion() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        when(catalog.findDocumentId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        AtomicReference<KnowledgeWriteBatch> written = new AtomicReference<>();
        when(writer.write(any())).thenAnswer(invocation -> {
            KnowledgeWriteBatch batch = invocation.getArgument(0);
            written.set(batch);
            return new KnowledgeWriteResult(
                    batch.document().id(),
                    batch.revision().id(),
                    true,
                    batch.chunks().size()
            );
        });
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        DocumentIngestionPipeline pipeline = pipeline(
                catalog,
                writer,
                parsers,
                256,
                512,
                1L
        );

        pipeline.publish(command("newline-lf-v1", IngestionIdentity.sha256(sourceBytes())));

        assertThat(written.get().expectedDocumentProcessingConfigVersion()).isEqualTo(1L);
        assertThat(written.get().document().metadata())
                .containsEntry("documentProcessingConfigVersion", "1")
                .containsEntry("chunkerProviderId", "STRUCTURAL")
                .containsEntry("chunkingFinalChunkCount", "1")
                .containsKeys(
                        "parseDurationNanos",
                        "cleaningDurationNanos",
                        "chunkingDurationNanos"
                );
        assertThat(written.get().document().metadata().get("chunkerContract"))
                .startsWith("managed-chunker-v2:provider=STRUCTURAL:")
                .contains("tokenizer=UTF8_BYTE_BUDGET")
                .contains("minimum=128:target=256:maximum=512:overlap=32");
    }

    @Test
    void effectiveConfigurationChangesRevisionWhileImmutableVersionStaysOne() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        when(catalog.findDocumentId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(writer.write(any())).thenAnswer(invocation -> {
            KnowledgeWriteBatch batch = invocation.getArgument(0);
            return new KnowledgeWriteResult(
                    batch.document().id(),
                    batch.revision().id(),
                    true,
                    batch.chunks().size()
            );
        });
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        String hash = IngestionIdentity.sha256(sourceBytes());

        var firstDefinition = pipeline(catalog, writer, parsers, 512, 1_024, 1L)
                .publish(command("newline-lf-v1", hash));
        var differentDefinition = pipeline(catalog, writer, parsers, 768, 1_024, 1L)
                .publish(command("newline-lf-v1", hash));

        assertThat(differentDefinition.writeResult().revisionId())
                .isNotEqualTo(firstDefinition.writeResult().revisionId());
    }

    @Test
    void persistsMetadataOnlyElementsButChunksOnlyIndexableElements() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        AtomicReference<KnowledgeWriteBatch> written = new AtomicReference<>();
        when(catalog.findDocumentId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(writer.write(any())).thenAnswer(invocation -> {
            KnowledgeWriteBatch batch = invocation.getArgument(0);
            written.set(batch);
            return new KnowledgeWriteResult(
                    batch.document().id(),
                    batch.revision().id(),
                    true,
                    batch.chunks().size()
            );
        });
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        var cleaning = new CleaningConfiguration(
                CleaningAction.KEEP,
                CleaningAction.KEEP,
                CleaningAction.KEEP,
                CleaningAction.KEEP,
                CleaningAction.METADATA_ONLY
        );
        var pipeline = pipeline(
                catalog,
                writer,
                parsers,
                512,
                1_024,
                1L,
                new DeterministicDocumentCleaningPolicy(),
                cleaning
        );
        byte[] source = """
                ---
                owner: platform
                ---
                # Runbook

                检查连接池。
                """.getBytes(StandardCharsets.UTF_8);

        var publication = pipeline.publish(command(
                "newline-lf-v1",
                source,
                IngestionIdentity.sha256(source)
        ));

        assertThat(publication.elementCount()).isEqualTo(3);
        assertThat(written.get().elements())
                .extracting(element -> element.ordinal())
                .containsExactly(0, 1, 2);
        assertThat(written.get().elements().getFirst().attributes())
                .containsEntry("role", "FRONT_MATTER")
                .containsEntry("cleaningDisposition", "METADATA_ONLY")
                .containsEntry("cleaningReasonCode", "FRONT_MATTER_METADATA_ONLY");
        assertThat(written.get().chunks())
                .allSatisfy(chunk -> {
                    assertThat(chunk.elementIds())
                            .doesNotContain(written.get().elements().getFirst().id());
                    assertThat(chunk.content()).doesNotContain("owner: platform");
                });
        assertThat(written.get().document().metadata())
                .containsEntry(
                        "cleanerContract",
                        "deterministic-cleaning-v2:h=KEEP,f=KEEP,p=KEEP,w=KEEP,"
                                + "fm=METADATA_ONLY,hidden=REMOVE,blank=REMOVE"
                )
                .containsEntry("cleaningMetadataOnlyElementCount", "1")
                .containsEntry(
                        "cleaningDecisionCounts",
                        "FRONT_MATTER_METADATA_ONLY=1"
                );
    }

    @Test
    void failsBeforeWritingWhenCleaningRemovesEveryElement() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        DocumentCleaningPolicy cleaner = mock(DocumentCleaningPolicy.class);
        when(catalog.findDocumentId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(cleaner.contract(any())).thenReturn("test-cleaning-v1");
        when(cleaner.clean(any(), any())).thenAnswer(invocation -> {
            ParsedDocument parsed = invocation.getArgument(0);
            return new DocumentCleaningResult(
                    List.of(),
                    parsed.elements(),
                    Map.of("TEST_METADATA_ONLY", parsed.elements().size()),
                    "test-cleaning-v1"
            );
        });
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        var pipeline = pipeline(catalog, writer, parsers, 512, 1_024, 1L, cleaner);

        DocumentParseException failure = assertThrows(
                DocumentParseException.class,
                () -> pipeline.publish(command(
                        "newline-lf-v1",
                        IngestionIdentity.sha256(sourceBytes())
                ))
        );

        assertThat(failure)
                .hasMessage("document did not contain indexable text after cleaning");
        verify(writer, never()).write(any());
    }

    private static DocumentIngestionPipeline pipeline(
            KnowledgeCatalog catalog,
            KnowledgeWriter writer
    ) {
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        return pipeline(catalog, writer, parsers, 512, 1_024, 1L);
    }

    private static DocumentIngestionPipeline pipeline(
            KnowledgeCatalog catalog,
            KnowledgeWriter writer,
            DocumentParserRegistry parsers,
            int targetTokens,
            int maximumTokens,
            long configVersion
    ) {
        return pipeline(
                catalog,
                writer,
                parsers,
                targetTokens,
                maximumTokens,
                configVersion,
                new DeterministicDocumentCleaningPolicy()
        );
    }

    private static DocumentIngestionPipeline pipeline(
            KnowledgeCatalog catalog,
            KnowledgeWriter writer,
            DocumentParserRegistry parsers,
            int targetTokens,
            int maximumTokens,
            long configVersion,
            DocumentCleaningPolicy cleaner
    ) {
        return pipeline(
                catalog,
                writer,
                parsers,
                targetTokens,
                maximumTokens,
                configVersion,
                cleaner,
                CleaningConfiguration.defaults()
        );
    }

    private static DocumentIngestionPipeline pipeline(
            KnowledgeCatalog catalog,
            KnowledgeWriter writer,
            DocumentParserRegistry parsers,
            int targetTokens,
            int maximumTokens,
            long configVersion,
            DocumentCleaningPolicy cleaner,
            CleaningConfiguration cleaning
    ) {
        return new DocumentIngestionPipeline(
                catalog,
                new DocumentPublicationService(
                        writer,
                        Clock.fixed(
                                Instant.parse("2026-08-14T00:00:00Z"),
                                ZoneOffset.UTC
                        )
                ),
                new ExtractionEngine(
                        parsers,
                        new KnowledgeChunkerFactory(List.of(), List.of()),
                        cleaner
                ),
                IngestionPipelineTestConfigs.structural(
                        parsers,
                        targetTokens,
                        maximumTokens,
                        cleaning,
                        cleaner,
                        configVersion
                ),
                new FileIngestionProperties(
                        1_048_576,
                        4_194_304,
                        50,
                        1_000,
                        100_000,
                        1_000,
                        100
                )
        );
    }

    private static DocumentIngestionPipeline.DocumentInput command(
            String normalizerContract,
            String contentHash
    ) {
        return command(normalizerContract, sourceBytes(), contentHash);
    }

    private static DocumentIngestionPipeline.DocumentInput command(
            String normalizerContract,
            byte[] sourceBytes,
            String contentHash
    ) {
        var spaceId = new KnowledgeSpaceId("engineering");
        return new DocumentIngestionPipeline.DocumentInput(
                new TenantId("tenant-a"),
                spaceId,
                new SourceDescriptor(
                        "api-upload:" + spaceId.value(),
                        SourceType.API,
                        "runbook.md",
                        "https://example.test/runbook.md",
                        Map.of()
                ),
                "Runbook",
                "text/markdown",
                "runbook.md",
                "zh-CN",
                80,
                Map.of(),
                normalizerContract,
                sourceBytes,
                contentHash,
                null
        );
    }

    private static byte[] sourceBytes() {
        return "# Runbook\n\n检查连接池。".getBytes(StandardCharsets.UTF_8);
    }
}
