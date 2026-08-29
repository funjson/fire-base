package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.ingestion.config.SpaceDocumentProcessingConfigResolver;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionGateReport;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import dev.infinityknowledge.spi.objectstorage.StoredObject;
import dev.infinityknowledge.spi.objectstorage.StoredObjectMetadata;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 TEST_ONLY 覆盖快照与基准运行在 Runtime 创建边界上的安全约束。 */
class ExtractionRunServiceTestConfigTest {

    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final PrincipalId ADMIN = new PrincipalId("admin");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private static final Instant NOW = Instant.parse("2026-08-22T06:00:00Z");
    private static final String DATASET = "extraction-core-v1";

    @Test
    void persistsTestConfigSnapshotWithTheSpaceConfigurationVersion() {
        RecordingStore store = new RecordingStore();
        RecordingObjectStorage objects = new RecordingObjectStorage();
        var testConfig = testConfigSnapshot();

        create(service(store, objects), ExtractionMode.TEST_ONLY, DATASET, null, testConfig);

        assertNotNull(store.beginRequest);
        assertEquals(1L, store.beginRequest.configVersion());
        assertEquals(testConfig, store.beginRequest.configSnapshot());
        assertEquals(512, store.beginRequest.configSnapshot().chunker().maximumTokens());
    }

    @Test
    void testOverrideOnlyRequiresTheSpaceStoredConfiguration() {
        RecordingStore store = new RecordingStore();
        RecordingObjectStorage objects = new RecordingObjectStorage();
        SpaceDocumentProcessingConfigResolver resolver =
                new SpaceDocumentProcessingConfigResolver() {
                    @Override
                    public SpaceDocumentProcessingConfigStore
                            .SpaceDocumentProcessingConfig resolve(
                                    TenantId tenantId,
                                    KnowledgeSpaceId spaceId
                            ) {
                        throw new AssertionError(
                                "fixed configuration executability must not be checked"
                        );
                    }

                    @Override
                    public SpaceDocumentProcessingConfigStore
                            .SpaceDocumentProcessingConfig resolveStored(
                                    TenantId tenantId,
                                    KnowledgeSpaceId spaceId
                            ) {
                        return activeConfig();
                    }
                };
        var service = new ExtractionRunService(
                store,
                objects,
                resolver,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        create(service, ExtractionMode.TEST_ONLY, DATASET, null, testConfigSnapshot());

        assertNotNull(store.beginRequest);
        assertEquals(testConfigSnapshot(), store.beginRequest.configSnapshot());
    }

    @Test
    void rejectsTestConfigForIngestBeforeCreatingRunOrWritingOss() {
        RecordingStore store = new RecordingStore();
        RecordingObjectStorage objects = new RecordingObjectStorage();

        assertThrows(IllegalArgumentException.class, () -> create(
                service(store, objects),
                ExtractionMode.INGEST,
                null,
                null,
                testConfigSnapshot()
        ));

        assertNull(store.beginRequest);
        assertEquals(0, objects.putCount);
    }

    @Test
    void rejectsForgedTestConfigFingerprintBeforeCreatingRunOrWritingOss() {
        RecordingStore store = new RecordingStore();
        RecordingObjectStorage objects = new RecordingObjectStorage();
        ExtractionConfigSnapshot valid = testConfigSnapshot();
        ExtractionConfigSnapshot forged = new ExtractionConfigSnapshot(
                valid.processingContract(),
                valid.normalizerContract(),
                valid.parserSelections(),
                valid.cleaning(),
                valid.chunker(),
                "0".repeat(64)
        );

        assertThrows(IllegalArgumentException.class, () -> create(
                service(store, objects),
                ExtractionMode.TEST_ONLY,
                DATASET,
                null,
                forged
        ));

        assertNull(store.beginRequest);
        assertEquals(0, objects.putCount);
    }

    @Test
    void rejectsBaselineFromAnotherDataset() {
        RecordingStore store = new RecordingStore();
        RecordingObjectStorage objects = new RecordingObjectStorage();
        UUID baselineId = UUID.randomUUID();
        store.baseline = baseline(
                        baselineId,
                        ExtractionMode.TEST_ONLY,
                        ExtractionRunStore.RunStatus.SUCCEEDED,
                        "other-dataset-v1",
                        ExtractionGateStatus.PASSED
                );

        assertThrows(IllegalArgumentException.class, () -> create(
                service(store, objects),
                ExtractionMode.TEST_ONLY,
                DATASET,
                baselineId,
                testConfigSnapshot()
        ));

        assertNull(store.beginRequest);
    }

    @Test
    void rejectsBaselineWithoutComparableGateReport() {
        RecordingStore store = new RecordingStore();
        RecordingObjectStorage objects = new RecordingObjectStorage();
        UUID baselineId = UUID.randomUUID();
        store.baseline = baseline(
                        baselineId,
                        ExtractionMode.TEST_ONLY,
                        ExtractionRunStore.RunStatus.SUCCEEDED,
                        DATASET,
                        ExtractionGateStatus.NOT_EVALUATED
                );

        assertThrows(IllegalArgumentException.class, () -> create(
                service(store, objects),
                ExtractionMode.TEST_ONLY,
                DATASET,
                baselineId,
                testConfigSnapshot()
        ));

        assertNull(store.beginRequest);
    }

    @Test
    void rejectsBaselineThatUsesAnotherDocumentLanguage() {
        RecordingStore store = new RecordingStore();
        RecordingObjectStorage objects = new RecordingObjectStorage();
        UUID baselineId = UUID.randomUUID();
        store.baseline = baseline(
                        baselineId,
                        ExtractionMode.TEST_ONLY,
                        ExtractionRunStore.RunStatus.SUCCEEDED,
                        DATASET,
                        ExtractionGateStatus.PASSED
                );

        assertThrows(IllegalArgumentException.class, () -> create(
                service(store, objects),
                ExtractionMode.TEST_ONLY,
                DATASET,
                baselineId,
                "en-US",
                testConfigSnapshot()
        ));

        assertNull(store.beginRequest);
    }

    @Test
    void acceptsSucceededBaselineWhoseBusinessGateFailed() {
        RecordingStore store = new RecordingStore();
        RecordingObjectStorage objects = new RecordingObjectStorage();
        UUID baselineId = UUID.randomUUID();
        store.baseline = baseline(
                        baselineId,
                        ExtractionMode.TEST_ONLY,
                        ExtractionRunStore.RunStatus.SUCCEEDED,
                        DATASET,
                        ExtractionGateStatus.FAILED
                );

        assertDoesNotThrow(() -> create(
                service(store, objects),
                ExtractionMode.TEST_ONLY,
                DATASET,
                baselineId,
                testConfigSnapshot()
        ));

        assertNotNull(store.beginRequest);
    }

    private static ExtractionRunService service(
            ExtractionRunStore store,
            ObjectStorage objects
    ) {
        return new ExtractionRunService(
                store,
                objects,
                (tenantId, spaceId) -> activeConfig(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static void create(
            ExtractionRunService service,
            ExtractionMode mode,
            String datasetId,
            UUID baselineRunId,
            ExtractionConfigSnapshot testConfig
    ) {
        create(service, mode, datasetId, baselineRunId, "zh-CN", testConfig);
    }

    private static void create(
            ExtractionRunService service,
            ExtractionMode mode,
            String datasetId,
            UUID baselineRunId,
            String language,
            ExtractionConfigSnapshot testConfig
    ) {
        byte[] source = "# Test config".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        service.create(
                TENANT,
                ADMIN,
                SPACE,
                mode,
                language,
                datasetId,
                baselineRunId,
                testConfig,
                1,
                1_024L,
                (index, remainingBytes) -> new ExtractionRunService.SourceUpload(
                        "test-config.md",
                        "text/markdown",
                        source,
                        IngestionIdentity.sha256(source)
                )
        );
    }

    private static ExtractionConfigSnapshot testConfigSnapshot() {
        return ExtractionConfigSnapshots.capture(
                testConfig(),
                testConfig().processingContract(),
                DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
        );
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            activeConfig() {
        return config(1L, 256);
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            testConfig() {
        return config(0L, 512);
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig config(
            long version,
            int maximumTokens
    ) {
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                TENANT,
                SPACE,
                Map.of("text/markdown", "markdown-structure"),
                SpaceDocumentProcessingConfigStore.CleaningConfiguration.defaults(),
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
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
                NOW
        );
    }

    private static DocumentProcessingContract contract(int maximumTokens) {
        return DocumentProcessingContract.create(
                DocumentProcessingContractFactory.PIPELINE_CONTRACT,
                DocumentProcessingContractFactory.NORMALIZER_SCHEMA_CONTRACT,
                Map.of("text/markdown", "markdown-parser-v1"),
                "cleaner-v1",
                "chunker-max-" + maximumTokens
        );
    }

    private static ExtractionRunStore.RunSnapshot baseline(
            UUID runId,
            ExtractionMode mode,
            ExtractionRunStore.RunStatus status,
            String datasetId,
            ExtractionGateStatus gateStatus
    ) {
        ExtractionConfigSnapshot snapshot = ExtractionConfigSnapshots.capture(
                activeConfig(),
                activeConfig().processingContract(),
                DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
        );
        ExtractionGateReport report = gateStatus == ExtractionGateStatus.NOT_EVALUATED
                ? null : gateReport(gateStatus, datasetId, snapshot.fingerprint());
        return new ExtractionRunStore.RunSnapshot(
                runId,
                TENANT,
                SPACE,
                mode,
                status,
                "zh-CN",
                1L,
                snapshot,
                datasetId,
                null,
                gateStatus,
                report,
                ADMIN,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(59),
                NOW.minusSeconds(58),
                NOW.minusSeconds(1),
                NOW.minusSeconds(2),
                List.of()
        );
    }

    private static ExtractionGateReport gateReport(
            ExtractionGateStatus status,
            String datasetId,
            String fingerprint
    ) {
        boolean passed = status == ExtractionGateStatus.PASSED;
        var findings = passed
                ? List.<ExtractionGateReport.Finding>of()
                : List.of(new ExtractionGateReport.Finding(
                        "TOKEN_OVERFLOW",
                        "测试配置存在超出预算的 Chunk"
                ));
        return new ExtractionGateReport(
                status,
                datasetId,
                datasetId,
                fingerprint,
                NOW.minusSeconds(1),
                List.of(new ExtractionGateReport.CaseResult(
                        "case-1",
                        "a".repeat(64),
                        passed,
                        1.0D,
                        passed ? 0 : 1,
                        0,
                        1.0D,
                        0,
                        findings
                )),
                null
        );
    }

    /** 只实现创建用例会触达的方法，避免 Runtime 测试反向依赖 Mockito。 */
    private static final class RecordingStore implements ExtractionRunStore {
        private BeginRequest beginRequest;
        private RunSnapshot baseline;

        @Override
        public RunSnapshot begin(BeginRequest request) {
            beginRequest = request;
            return null;
        }

        @Override
        public void appendSource(
                UUID runId,
                SourceAsset sourceAsset,
                PublicationAttributes publication,
                Instant now
        ) {
        }

        @Override
        public RunSnapshot seal(UUID runId, int expectedItems, Instant now) {
            return null;
        }

        @Override
        public void failCreation(UUID runId, String errorCode, Instant now) {
            throw new AssertionError("creation was not expected to fail after begin");
        }

        @Override
        public Optional<RunSnapshot> find(
                TenantId tenantId,
                KnowledgeSpaceId spaceId,
                UUID runId
        ) {
            if (baseline != null && baseline.id().equals(runId)) {
                return Optional.of(baseline);
            }
            return Optional.empty();
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
        public Optional<RunSnapshot> claimNext(
                ExtractionMode mode,
                String workerId,
                Instant staleBefore,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int failInterruptedUploads(Instant createdBefore, Instant now, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean heartbeat(UUID runId, String workerId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancellationRequested(UUID runId, String workerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean startItem(UUID runId, UUID itemId, String workerId, Instant now) {
            throw new UnsupportedOperationException();
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
                ItemDiagnostics diagnostics,
                dev.infinityknowledge.spi.extraction.ExtractionPreview preview,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean succeedPublishedItem(
                UUID runId,
                UUID itemId,
                String workerId,
                ItemDiagnostics diagnostics,
                dev.infinityknowledge.spi.extraction.ExtractionPreview preview,
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
        public boolean failItem(
                UUID runId,
                UUID itemId,
                String workerId,
                ItemStage stage,
                String errorCode,
                Instant now
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean finishRun(
                UUID runId,
                String workerId,
                RunStatus finalStatus,
                String errorCode,
                ExtractionGateReport gateReport,
                Instant now
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

    /** 返回与写入请求完全一致的 OSS 元数据，并记录是否发生了写入。 */
    private static final class RecordingObjectStorage implements ObjectStorage {
        private int putCount;

        @Override
        public StoredObjectMetadata put(ObjectWriteRequest request, InputStream content) {
            putCount++;
            return new StoredObjectMetadata(
                    request.address(),
                    "stored-" + request.address().objectId(),
                    request.originalFileName(),
                    request.mediaType(),
                    request.contentLength(),
                    request.checksumSha256(),
                    "etag",
                    Map.of(),
                    NOW
            );
        }

        @Override
        public Optional<StoredObject> get(ObjectAddress address) {
            return Optional.empty();
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
