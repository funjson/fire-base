package dev.infinityknowledge.runtime.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.runtime.extraction.ExtractionConfigSnapshots;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionPreview;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.BeginRequest;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemDiagnostics;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.ItemStage;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.PublicationAttributes;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunItem;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.RunStatus;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.SourceAsset;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContractMismatchException;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.ingestion.PublishedSourceCatalog;
import dev.infinityknowledge.spi.ingestion.PublishedSourceCatalog.PublishedSource;
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

/** 验证正式多文件摄取的无覆盖、幂等和发布恢复边界。 */
class IngestionRunProcessorTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private static final Instant NOW = Instant.parse("2026-08-22T02:00:00Z");

    @Test
    void publishesNewSourceThroughTheSharedExtractionEngine() {
        byte[] source = "# Runbook\n\n检查连接池。".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(source, ItemStage.STORED));
        TestObjectStorage objects = new TestObjectStorage(source);
        TestWriter writer = new TestWriter();

        assertTrue(processor(store, objects, new TestCatalog(), writer).pollOnce());

        assertEquals(RunStatus.SUCCEEDED, store.finalStatus);
        assertEquals(1, store.beginPublicationCalls);
        assertNotNull(store.publishedDocumentId);
        assertNotNull(store.publishedRevisionId);
        assertNotNull(store.diagnostics);
        assertNotNull(store.preview);
        assertEquals(1, objects.readCount.get());
        assertNotNull(writer.batch);
        assertNotNull(writer.batch.sourceObject());
        assertEquals("api-upload:engineering", writer.batch.document().source().connectorId());
        assertEquals("runbook.md", writer.batch.document().source().externalId());
    }

    @Test
    void skipsSameContentWithoutReadingOrParsingAgain() {
        byte[] source = "same".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(source, ItemStage.STORED));
        TestObjectStorage objects = new TestObjectStorage(source);
        TestWriter writer = new TestWriter();
        PublishedSource duplicate = published(source);
        TestCatalog catalog = new TestCatalog();
        catalog.contentMatch = duplicate;

        assertTrue(processor(store, objects, catalog, writer).pollOnce());

        assertEquals(RunStatus.SUCCEEDED, store.finalStatus);
        assertEquals(duplicate.documentId(), store.skippedDocumentId);
        assertEquals(duplicate.revisionId(), store.skippedRevisionId);
        assertEquals(0, objects.readCount.get());
        assertNull(writer.batch);
    }

    @Test
    void rejectsProcessingContractDriftBeforeLookupOrReadingOss() {
        byte[] source = "# Runbook".getBytes(StandardCharsets.UTF_8);
        TestObjectStorage objects = new TestObjectStorage(source);
        TestCatalog catalog = new TestCatalog();
        TestRunStore store = new TestRunStore(run(source, ItemStage.STORED, true));
        TestWriter writer = new TestWriter();

        assertTrue(processor(store, objects, catalog, writer).pollOnce());

        assertEquals(RunStatus.FAILED, store.finalStatus);
        assertEquals(
                DocumentProcessingContractMismatchException.CODE,
                store.finalErrorCode
        );
        assertEquals(0, catalog.lookupCount);
        assertEquals(0, objects.readCount.get());
        assertNull(writer.batch);
    }

    @Test
    void rejectsExternalIdConflictWithoutOverwriting() {
        byte[] source = "new".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(source, ItemStage.STORED));
        TestCatalog catalog = new TestCatalog();
        catalog.identityMatch = published("old".getBytes(StandardCharsets.UTF_8));
        TestWriter writer = new TestWriter();

        assertTrue(processor(
                store,
                new TestObjectStorage(source),
                catalog,
                writer
        ).pollOnce());

        assertEquals("EXTERNAL_ID_CONFLICT", store.itemErrorCode);
        assertEquals(RunStatus.FAILED, store.finalStatus);
        assertNull(writer.batch);
    }

    @Test
    void resumesPublishingWithoutResettingTheItemToParse() {
        byte[] source = "# Recovery".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(source, ItemStage.PUBLISHING));
        TestWriter writer = new TestWriter();

        assertTrue(processor(
                store,
                new TestObjectStorage(source),
                new TestCatalog(),
                writer
        ).pollOnce());

        assertEquals(0, store.startItemCalls);
        assertEquals(0, store.beginPublicationCalls);
        assertNotNull(store.publishedDocumentId);
        assertEquals(RunStatus.SUCCEEDED, store.finalStatus);
    }

    @Test
    void reportsContractMismatchAtThePublicationStage() {
        byte[] source = "# Changed".getBytes(StandardCharsets.UTF_8);
        TestRunStore store = new TestRunStore(run(source, ItemStage.STORED));
        TestWriter writer = new TestWriter();
        writer.configChanged = true;

        assertTrue(processor(
                store,
                new TestObjectStorage(source),
                new TestCatalog(),
                writer
        ).pollOnce());

        assertEquals(
                DocumentProcessingContractMismatchException.CODE,
                store.itemErrorCode
        );
        assertEquals(ItemStage.PUBLISHING, store.failedStage);
        assertEquals(RunStatus.FAILED, store.finalStatus);
    }

    private static IngestionRunProcessor processor(
            TestRunStore store,
            ObjectStorage objects,
            PublishedSourceCatalog catalog,
            KnowledgeWriter writer
    ) {
        var engine = engine();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new IngestionRunProcessor(
                store,
                catalog,
                objects,
                engine,
                new DocumentPublicationService(writer, clock),
                DocumentParseLimits.defaults(),
                "ingestion-worker-a",
                Duration.ofMinutes(15),
                Duration.ofMinutes(30),
                clock
        );
    }

    private static RunSnapshot run(byte[] source, ItemStage stage) {
        return run(source, stage, false);
    }

    private static RunSnapshot run(
            byte[] source,
            ItemStage stage,
            boolean driftProcessingContract
    ) {
        SpaceDocumentProcessingConfig config = config();
        if (driftProcessingContract) {
            DocumentProcessingContract current = config.processingContract();
            config = config.withProcessingContract(DocumentProcessingContract.create(
                    current.pipelineContract(),
                    current.normalizerSchemaContract(),
                    current.parserContracts(),
                    current.cleanerContract() + ";implementation=drifted",
                    current.chunkerContract()
            ));
        }
        UUID runId = UUID.randomUUID();
        SourceAsset asset = new SourceAsset(
                UUID.randomUUID(),
                TENANT,
                SPACE,
                "source-asset-test",
                "storage-source",
                "runbook.md",
                "text/markdown",
                source.length,
                IngestionIdentity.sha256(source),
                NOW
        );
        var status = stage == ItemStage.PUBLISHING
                ? ExtractionRunStore.ItemStatus.RUNNING
                : ExtractionRunStore.ItemStatus.QUEUED;
        RunItem item = new RunItem(
                UUID.randomUUID(),
                asset,
                new PublicationAttributes("runbook.md", "Runbook", 80),
                status,
                stage,
                null,
                null,
                null,
                null,
                null,
                stage == ItemStage.PUBLISHING ? NOW : null,
                null
        );
        ExtractionConfigSnapshot snapshot = ExtractionConfigSnapshots.capture(
                config,
                config.processingContract(),
                DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
        );
        return new RunSnapshot(
                runId,
                TENANT,
                SPACE,
                ExtractionMode.INGEST,
                RunStatus.RUNNING,
                "zh-CN",
                config.version(),
                snapshot,
                null,
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
                List.of(item)
        );
    }

    private static SpaceDocumentProcessingConfig config() {
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
                1L,
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

    private static PublishedSource published(byte[] source) {
        return new PublishedSource(
                new DocumentId(UUID.randomUUID()),
                UUID.randomUUID(),
                IngestionIdentity.sha256(source)
        );
    }

    private static final class TestCatalog implements PublishedSourceCatalog {
        private PublishedSource identityMatch;
        private PublishedSource contentMatch;
        private int lookupCount;

        @Override
        public Optional<PublishedSource> findByExternalId(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                String connectorId,
                String externalId
        ) {
            lookupCount++;
            return Optional.ofNullable(identityMatch);
        }

        @Override
        public Optional<PublishedSource> findByContentHash(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                String contentHash
        ) {
            lookupCount++;
            return Optional.ofNullable(contentMatch);
        }
    }

    private static final class TestWriter implements KnowledgeWriter {
        private KnowledgeWriteBatch batch;
        private boolean configChanged;

        @Override
        public KnowledgeWriteResult write(KnowledgeWriteBatch value) {
            if (configChanged) {
                throw new DocumentProcessingContractMismatchException();
            }
            batch = value;
            return new KnowledgeWriteResult(
                    value.document().id(),
                    value.revision().id(),
                    true,
                    value.chunks().size(),
                    true
            );
        }
    }

    private static final class TestObjectStorage implements ObjectStorage {
        private final byte[] source;
        private final AtomicInteger readCount = new AtomicInteger();

        private TestObjectStorage(byte[] source) {
            this.source = source.clone();
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
                    IngestionIdentity.sha256(source),
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

    private static final class TestRunStore implements ExtractionRunStore {
        private RunSnapshot claimed;
        private RunStatus finalStatus;
        private String finalErrorCode;
        private String itemErrorCode;
        private ItemStage failedStage;
        private ItemDiagnostics diagnostics;
        private ExtractionPreview preview;
        private DocumentId publishedDocumentId;
        private UUID publishedRevisionId;
        private DocumentId skippedDocumentId;
        private UUID skippedRevisionId;
        private int startItemCalls;
        private int beginPublicationCalls;

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
            assertEquals(ExtractionMode.INGEST, mode);
            RunSnapshot value = claimed;
            claimed = null;
            return Optional.ofNullable(value);
        }

        @Override
        public int failInterruptedUploads(Instant createdBefore, Instant now, int limit) {
            return 0;
        }

        @Override
        public boolean heartbeat(UUID runId, String workerId, Instant now) {
            return true;
        }

        @Override
        public boolean cancellationRequested(UUID runId, String workerId) {
            return false;
        }

        @Override
        public boolean startItem(UUID runId, UUID itemId, String workerId, Instant now) {
            startItemCalls++;
            return true;
        }

        @Override
        public boolean beginPublication(UUID runId, UUID itemId, String workerId, Instant now) {
            beginPublicationCalls++;
            return true;
        }

        @Override
        public boolean succeedPublishedItem(
                UUID runId,
                UUID itemId,
                String workerId,
                ItemDiagnostics value,
                ExtractionPreview itemPreview,
                DocumentId documentId,
                UUID revisionId,
                Instant now
        ) {
            diagnostics = value;
            preview = itemPreview;
            publishedDocumentId = documentId;
            publishedRevisionId = revisionId;
            return true;
        }

        @Override
        public boolean skipDuplicateItem(
                UUID runId,
                UUID itemId,
                String workerId,
                DocumentId documentId,
                UUID revisionId,
                Instant now
        ) {
            skippedDocumentId = documentId;
            skippedRevisionId = revisionId;
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
            failedStage = stage;
            itemErrorCode = errorCode;
            return true;
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
            finalStatus = status;
            finalErrorCode = errorCode;
            return true;
        }

        @Override
        public boolean succeedItem(
                UUID runId,
                UUID itemId,
                String workerId,
                ItemDiagnostics itemDiagnostics,
                ExtractionPreview itemPreview,
                Instant now
        ) {
            throw new UnsupportedOperationException();
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
}
