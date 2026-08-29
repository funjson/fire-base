package dev.infinityknowledge.controlplane.application.ingestion.extraction;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.extraction.ExtractionRequest;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.runtime.extraction.ExtractionConfigSnapshots;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningAction;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证控制面 Adapter 使用真实引擎、同一 TokenCounter 和统一 Dataset Runner。 */
class DatasetExtractionGateEvaluatorTest {

    private static final TenantId TENANT = new TenantId("gate-tenant");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("gate-space");
    private static final Instant NOW = Instant.parse("2026-08-22T03:00:00Z");

    @Test
    void evaluatesBuiltInDatasetThroughTheRealPipeline() {
        ExtractionDatasetCatalog catalog = new ExtractionDatasetCatalog();
        var dataset = catalog.require(catalog.descriptors().getFirst().id());
        KnowledgeChunkerFactory chunkers = new KnowledgeChunkerFactory(List.of(), List.of());
        var engine = new ExtractionEngine(
                DocumentParserRegistry.standard(),
                chunkers,
                new DeterministicDocumentCleaningPolicy()
        );
        SpaceDocumentProcessingConfig config = config(engine);
        List<ExtractionRunStore.RunItem> items = new ArrayList<>();
        for (var value : dataset.cases()) {
            items.add(item(value));
        }
        var run = run(dataset.datasetVersion(), config, items);
        var evaluator = new DatasetExtractionGateEvaluator(
                catalog,
                chunkers,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var session = evaluator.start(run, config);
        for (int index = 0; index < dataset.cases().size(); index++) {
            var golden = dataset.cases().get(index);
            var item = items.get(index);
            var result = engine.extract(new ExtractionRequest(
                    TENANT,
                    SPACE,
                    new DocumentId(item.id()),
                    golden.mediaType(),
                    golden.sourceName(),
                    "und",
                    DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT,
                    golden.sourceBytes(),
                    golden.sourceSha256(),
                    DocumentParseLimits.defaults(),
                    config
            ));
            session.observe(item, result);
        }

        var report = session.complete();

        assertEquals(ExtractionGateStatus.PASSED, report.status());
        assertEquals(dataset.datasetVersion(), report.datasetId());
        assertEquals(dataset.cases().size(), report.cases().size());
        assertTrue(report.cases().stream().allMatch(value -> value.passed()));
    }

    private static SpaceDocumentProcessingConfig config(ExtractionEngine engine) {
        var request = new SpaceDocumentProcessingConfig(
                TENANT,
                SPACE,
                DocumentParserRegistry.standard().defaultParserSelections(),
                new CleaningConfiguration(
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.KEEP,
                        CleaningAction.METADATA_ONLY
                ),
                new ChunkerConfiguration(
                        KnowledgeChunkerFactory.STRUCTURAL,
                        "UTF8_BYTE_BUDGET",
                        64,
                        256,
                        512,
                        32,
                        "{}"
                ),
                0L,
                NOW
        );
        return new SpaceDocumentProcessingConfig(
                request.tenantId(),
                request.spaceId(),
                request.parserSelections(),
                request.cleaning(),
                request.chunker(),
                engine.processingContract(request),
                1L,
                null,
                NOW
        );
    }

    private static ExtractionRunStore.RunItem item(
            dev.infinityknowledge.evaluation.extraction.ExtractionAcceptanceDataset.Case value
    ) {
        UUID itemId = UUID.nameUUIDFromBytes(
                value.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        var source = new ExtractionRunStore.SourceAsset(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                "object-" + itemId,
                "storage-" + itemId,
                value.sourceName(),
                value.mediaType(),
                value.sourceBytes().length,
                value.sourceSha256(),
                NOW
        );
        return new ExtractionRunStore.RunItem(
                itemId,
                source,
                new ExtractionRunStore.PublicationAttributes(
                        value.sourceName(),
                        value.sourceName(),
                        80
                ),
                ExtractionRunStore.ItemStatus.SUCCEEDED,
                ExtractionRunStore.ItemStage.COMPLETED,
                null,
                null,
                null,
                null,
                null,
                NOW,
                NOW
        );
    }

    private static ExtractionRunStore.RunSnapshot run(
            String datasetId,
            SpaceDocumentProcessingConfig config,
            List<ExtractionRunStore.RunItem> items
    ) {
        return new ExtractionRunStore.RunSnapshot(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                ExtractionMode.TEST_ONLY,
                ExtractionRunStore.RunStatus.RUNNING,
                "und",
                config.version(),
                ExtractionConfigSnapshots.capture(
                        config,
                        config.processingContract(),
                        DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
                ),
                datasetId,
                null,
                ExtractionGateStatus.NOT_EVALUATED,
                null,
                new PrincipalId("admin"),
                null,
                NOW,
                NOW,
                NOW,
                null,
                NOW,
                items
        );
    }

}
