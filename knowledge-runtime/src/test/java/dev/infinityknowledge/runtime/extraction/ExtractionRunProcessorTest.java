package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionPreview;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import dev.infinityknowledge.spi.objectstorage.StoredObject;
import dev.infinityknowledge.spi.objectstorage.StoredObjectMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证抽取试验 Processor 的 TEST_ONLY、配置版本和取消边界。 */
class ExtractionRunProcessorTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");

    @Test
    void processesOneFileWithTheSharedExtractionEngine() {
        byte[] source = "# Runbook\n\n检查连接池。".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(1L, source, ExtractionRunStore.RunStatus.RUNNING));
        TestObjectStorage objects = new TestObjectStorage(source);
        ExtractionRunProcessor processor = processor(store, objects, config(1L));

        assertTrue(processor.pollOnce());

        assertEquals(ExtractionRunStore.RunStatus.SUCCEEDED, store.finalStatus);
        assertNull(store.finalErrorCode);
        assertNotNull(store.diagnostics);
        assertEquals("markdown-structure", store.diagnostics.parserId());
        assertTrue(store.diagnostics.processorVersion().startsWith("pipeline-v"));
        assertTrue(store.diagnostics.elementCount() > 0);
        assertTrue(store.diagnostics.chunkCount() > 0);
        assertTrue(store.diagnostics.cleaning().indexableElements() > 0);
        assertNotNull(store.diagnostics.cleaning().reasonCodeCounts());
        assertEquals(
                store.diagnostics.chunkCount(),
                store.diagnostics.chunking().finalChunkCount()
        );
        assertTrue(store.diagnostics.chunking().structuralHardBreaks() >= 0);
        assertTrue(store.diagnostics.chunking().maximumChunkUnits() > 0);
        assertNotNull(store.preview);
        assertFalse(store.preview.elements().isEmpty());
        assertFalse(store.preview.chunks().isEmpty());
        assertNotNull(store.preview.elements().getFirst().sourceRange());
        assertNotNull(store.preview.chunks().getFirst().sourceSpans()
                .getFirst().artifactRange());
        assertEquals(1, objects.readCount.get());
    }

    @Test
    void executesTheImmutableConfigSnapshotWithoutReadingCurrentSpaceConfig() {
        byte[] source = "# Runbook".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(1L, source, ExtractionRunStore.RunStatus.RUNNING));
        TestObjectStorage objects = new TestObjectStorage(source);
        ExtractionRunProcessor processor = processor(store, objects, config(1L));

        assertTrue(processor.pollOnce());

        assertEquals(ExtractionRunStore.RunStatus.SUCCEEDED, store.finalStatus);
        assertNull(store.finalErrorCode);
        assertEquals(1, objects.readCount.get());
    }

    @Test
    void finalizesAReclaimedCancellationWithoutReadingOss() {
        byte[] source = "# Runbook".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(
                1L,
                source,
                ExtractionRunStore.RunStatus.CANCEL_REQUESTED
        ));
        TestObjectStorage objects = new TestObjectStorage(source);
        ExtractionRunProcessor processor = processor(store, objects, config(1L));

        assertTrue(processor.pollOnce());

        assertEquals(ExtractionRunStore.RunStatus.CANCELLED, store.finalStatus);
        assertEquals(0, objects.readCount.get());
    }

    @Test
    void rejectsProcessingContractDriftBeforeReadingOss() {
        byte[] source = "# Runbook".getBytes(StandardCharsets.UTF_8);
        var current = config(1L).processingContract();
        var drifted = DocumentProcessingContract.create(
                current.pipelineContract(),
                current.normalizerSchemaContract(),
                current.parserContracts(),
                current.cleanerContract() + ";implementation=drifted",
                current.chunkerContract()
        );
        TestRunStore store = new TestRunStore(run(
                1L,
                source,
                ExtractionRunStore.RunStatus.RUNNING,
                null,
                drifted
        ));
        TestObjectStorage objects = new TestObjectStorage(source);

        assertTrue(processor(store, objects, config(1L)).pollOnce());

        assertEquals(ExtractionRunStore.RunStatus.FAILED, store.finalStatus);
        assertEquals(
                DocumentProcessingContractMismatchException.CODE,
                store.finalErrorCode
        );
        assertEquals(0, objects.readCount.get());
    }

    @Test
    void finalizesCancellationThatRacesWithSuccess() {
        byte[] source = "# Runbook".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(1L, source, ExtractionRunStore.RunStatus.RUNNING));
        store.rejectFirstFinalizationAsCancellation = true;
        ExtractionRunProcessor processor = processor(
                store,
                new TestObjectStorage(source),
                config(1L)
        );

        assertTrue(processor.pollOnce());

        assertEquals(2, store.finalizationAttempts);
        assertEquals(ExtractionRunStore.RunStatus.CANCELLED, store.finalStatus);
    }

    @Test
    void recoversInterruptedMultipartBeforePolling() {
        TestRunStore store = new TestRunStore(null);
        ExtractionRunProcessor processor = processor(
                store,
                new TestObjectStorage(new byte[0]),
                config(1L)
        );

        assertFalse(processor.pollOnce());

        assertEquals(1, store.interruptedRecoveryCalls);
        assertEquals(NOW.minus(Duration.ofMinutes(30)), store.interruptedCreatedBefore);
    }

    @Test
    void rejectsContentWhoseShaDoesNotMatchTheCatalog() {
        byte[] catalogSource = "# Runbook A".getBytes(StandardCharsets.UTF_8);
        byte[] storedSource = "# Runbook B".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(
                1L,
                catalogSource,
                ExtractionRunStore.RunStatus.RUNNING
        ));
        ExtractionRunProcessor processor = processor(
                store,
                new TestObjectStorage(storedSource, IngestionIdentity.sha256(catalogSource)),
                config(1L)
        );

        assertTrue(processor.pollOnce());

        assertEquals("SOURCE_CHECKSUM_MISMATCH", store.itemErrorCode);
        assertEquals(ExtractionRunStore.RunStatus.FAILED, store.finalStatus);
    }

    @Test
    void keepsSuccessfulRunAndFailedDatasetGateAsIndependentResults() {
        byte[] source = "# Runbook\n\n检查连接池。".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(
                1L,
                source,
                ExtractionRunStore.RunStatus.RUNNING,
                "golden-v1"
        ));
        ExtractionGateEvaluator evaluator = (run, config) ->
                new ExtractionGateEvaluator.Session() {
                    @Override
                    public void observe(
                            ExtractionRunStore.RunItem item,
                            dev.infinityknowledge.ingestion.extraction.ExtractionResult result
                    ) {
                        // 测试只验证状态分离，真实 Observation 映射由 evaluation 模块覆盖。
                    }

                    @Override
                    public ExtractionGateReport complete() {
                        return new ExtractionGateReport(
                                ExtractionGateStatus.FAILED,
                                run.datasetId(),
                                "golden-v1",
                                run.configSnapshot().fingerprint(),
                                NOW,
                                List.of(new ExtractionGateReport.CaseResult(
                                        "case-a",
                                        IngestionIdentity.sha256(source),
                                        false,
                                        1.0D,
                                        0,
                                        0,
                                        0.5D,
                                        1,
                                        List.of(new ExtractionGateReport.Finding(
                                                "SOURCE_ACCOUNTING_GATE_FAILED",
                                                "语义来源核算率未达到硬门禁"
                                        ))
                                )),
                                null
                        );
                    }
                };
        ExtractionRunProcessor processor = processor(
                store,
                new TestObjectStorage(source),
                config(1L),
                evaluator
        );

        assertTrue(processor.pollOnce());

        assertEquals(ExtractionRunStore.RunStatus.SUCCEEDED, store.finalStatus);
        assertNull(store.finalErrorCode);
        assertNotNull(store.gateReport);
        assertEquals(ExtractionGateStatus.FAILED, store.gateReport.status());
    }

    private static ExtractionRunProcessor processor(
            TestRunStore store,
            ObjectStorage objectStorage,
            SpaceDocumentProcessingConfig config
    ) {
        return processor(
                store,
                objectStorage,
                config,
                ExtractionGateEvaluator.disabled()
        );
    }

    private static ExtractionRunProcessor processor(
            TestRunStore store,
            ObjectStorage objectStorage,
            SpaceDocumentProcessingConfig config,
            ExtractionGateEvaluator gateEvaluator
    ) {
        ExtractionEngine engine = engine();
        return new ExtractionRunProcessor(
                store,
                objectStorage,
                engine,
                gateEvaluator,
                DocumentParseLimits.defaults(),
                "worker-a",
                Duration.ofMinutes(15),
                Duration.ofMinutes(30),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static ExtractionRunStore.RunSnapshot run(
            long configVersion,
            byte[] source,
            ExtractionRunStore.RunStatus status
    ) {
        return run(configVersion, source, status, null);
    }

    private static ExtractionRunStore.RunSnapshot run(
            long configVersion,
            byte[] source,
            ExtractionRunStore.RunStatus status,
            String datasetId
    ) {
        return run(configVersion, source, status, datasetId, null);
    }

    private static ExtractionRunStore.RunSnapshot run(
            long configVersion,
            byte[] source,
            ExtractionRunStore.RunStatus status,
            String datasetId,
            DocumentProcessingContract forcedContract
    ) {
        UUID runId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        var asset = new ExtractionRunStore.SourceAsset(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                "extraction-test-source",
                "storage-source",
                "runbook.md",
                "text/markdown",
                source.length,
                IngestionIdentity.sha256(source),
                NOW
        );
        var item = new ExtractionRunStore.RunItem(
                itemId,
                asset,
                new ExtractionRunStore.PublicationAttributes("runbook.md", "runbook.md", 80),
                ExtractionRunStore.ItemStatus.QUEUED,
                ExtractionRunStore.ItemStage.STORED,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        SpaceDocumentProcessingConfig config = config(configVersion);
        if (forcedContract != null) {
            config = config.withProcessingContract(forcedContract);
        }
        return new ExtractionRunStore.RunSnapshot(
                runId,
                TENANT,
                SPACE,
                ExtractionMode.TEST_ONLY,
                status,
                "zh-CN",
                configVersion,
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
                status == ExtractionRunStore.RunStatus.CANCEL_REQUESTED ? NOW : null,
                null,
                NOW,
                List.of(item)
        );
    }

    private static SpaceDocumentProcessingConfig config(long version) {
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        var request = new SpaceDocumentProcessingConfig(
                TENANT,
                SPACE,
                parsers.defaultParserSelections(),
                CleaningConfiguration.defaults(),
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
                NOW
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
                NOW
        );
    }

    private static ExtractionEngine engine() {
        return new ExtractionEngine(
                DocumentParserRegistry.standard(),
                new KnowledgeChunkerFactory(List.of(), List.of()),
                new DeterministicDocumentCleaningPolicy()
        );
    }

    private static final class TestRunStore implements ExtractionRunStore {
        private RunSnapshot claimed;
        private RunStatus finalStatus;
        private String finalErrorCode;
        private ItemDiagnostics diagnostics;
        private ExtractionPreview preview;
        private ExtractionGateReport gateReport;
        private String itemErrorCode;
        private boolean cancellationRequested;
        private boolean rejectFirstFinalizationAsCancellation;
        private int finalizationAttempts;
        private int interruptedRecoveryCalls;
        private Instant interruptedCreatedBefore;

        private TestRunStore(RunSnapshot claimed) {
            this.claimed = claimed;
        }

        @Override
        public Optional<RunSnapshot> claimNext(
                ExtractionMode mode,
                String workerId,
                Instant staleBefore,
                Instant now
        ) {
            assertEquals(ExtractionMode.TEST_ONLY, mode);
            RunSnapshot next = claimed;
            claimed = null;
            return Optional.ofNullable(next);
        }

        @Override
        public int failInterruptedUploads(Instant createdBefore, Instant now, int limit) {
            interruptedRecoveryCalls++;
            interruptedCreatedBefore = createdBefore;
            return 0;
        }

        @Override
        public boolean heartbeat(UUID runId, String workerId, Instant now) {
            return true;
        }

        @Override
        public boolean cancellationRequested(UUID runId, String workerId) {
            return cancellationRequested;
        }

        @Override
        public boolean startItem(UUID runId, UUID itemId, String workerId, Instant now) {
            return true;
        }

        @Override
        public boolean beginPublication(
                UUID runId,
                UUID itemId,
                String workerId,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean succeedItem(
                UUID runId,
                UUID itemId,
                String workerId,
                ItemDiagnostics itemDiagnostics,
                ExtractionPreview preview,
                Instant now
        ) {
            diagnostics = itemDiagnostics;
            this.preview = preview;
            return true;
        }

        @Override
        public boolean failItem(
                UUID runId,
                UUID itemId,
                String workerId,
                ItemStage stage,
                String errorCode,
                Instant now
        ) {
            itemErrorCode = errorCode;
            return true;
        }

        @Override
        public boolean succeedPublishedItem(
                UUID runId,
                UUID itemId,
                String workerId,
                ItemDiagnostics diagnostics,
                ExtractionPreview preview,
                dev.infinityknowledge.domain.document.DocumentId documentId,
                UUID revisionId,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean skipDuplicateItem(
                UUID runId,
                UUID itemId,
                String workerId,
                dev.infinityknowledge.domain.document.DocumentId documentId,
                UUID revisionId,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean finishRun(
                UUID runId,
                String workerId,
                RunStatus status,
                String errorCode,
                ExtractionGateReport gateReport,
                Instant now
        ) {
            finalizationAttempts++;
            if (rejectFirstFinalizationAsCancellation && finalizationAttempts == 1) {
                cancellationRequested = true;
                return false;
            }
            finalStatus = status;
            finalErrorCode = errorCode;
            this.gateReport = gateReport;
            return true;
        }

        @Override
        public RunSnapshot begin(BeginRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendSource(
                UUID runId,
                SourceAsset sourceAsset,
                PublicationAttributes publication,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunSnapshot seal(UUID runId, int expectedItems, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void failCreation(UUID runId, String errorCode, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RunSnapshot> find(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                UUID runId
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RunSnapshot> list(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                int limit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RunSnapshot> requestCancel(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                UUID runId,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestObjectStorage implements ObjectStorage {
        private final byte[] source;
        private final String metadataChecksum;
        private final AtomicInteger readCount = new AtomicInteger();

        private TestObjectStorage(byte[] source) {
            this(source, IngestionIdentity.sha256(source));
        }

        private TestObjectStorage(byte[] source, String metadataChecksum) {
            this.source = source.clone();
            this.metadataChecksum = metadataChecksum;
        }

        @Override
        public Optional<StoredObject> get(ObjectAddress address) {
            readCount.incrementAndGet();
            var metadata = new StoredObjectMetadata(
                    address,
                    "storage-source",
                    "runbook.md",
                    "text/markdown",
                    source.length,
                    metadataChecksum,
                    "etag",
                    Map.of(),
                    NOW
            );
            return Optional.of(new StoredObject(metadata, new ByteArrayInputStream(source)));
        }

        @Override
        public StoredObjectMetadata put(ObjectWriteRequest request, InputStream content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredObjectMetadata> head(ObjectAddress address) {
            return Optional.empty();
        }

        @Override
        public void delete(ObjectAddress address) {
            throw new UnsupportedOperationException();
        }
    }
}
