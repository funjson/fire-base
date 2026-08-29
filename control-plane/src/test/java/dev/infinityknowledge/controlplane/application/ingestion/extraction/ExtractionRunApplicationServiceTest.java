package dev.infinityknowledge.controlplane.application.ingestion.extraction;

import dev.infinityknowledge.controlplane.application.ingestion.UploadedSourceReader;
import dev.infinityknowledge.controlplane.application.ingestion.DocumentProcessingCapabilities;
import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.controlplane.config.ingestion.ExtractionRunProperties;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.SourceSizeLimitExceededException;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.runtime.extraction.ExtractionRunService;
import dev.infinityknowledge.runtime.extraction.ExtractionConfigSnapshots;
import dev.infinityknowledge.ingestion.extraction.ExtractionEngine;
import dev.infinityknowledge.spi.extraction.ExtractionGateStatus;
import dev.infinityknowledge.spi.extraction.ExtractionMode;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import dev.infinityknowledge.spi.objectstorage.StoredObject;
import dev.infinityknowledge.spi.objectstorage.StoredObjectMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证多文件逐个保留、批次门禁和禁止补偿删除。 */
class ExtractionRunApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");

    @Test
    void uploadsAndRegistersEachFileWithoutAggregatingSourceBytes() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();
        ExtractionRunApplicationService service = service(store, objects, 1_024L);
        when(store.seal(any(), anyInt(), any())).thenAnswer(invocation ->
                snapshot(invocation.getArgument(0), 2)
        );

        var result = service.create(
                principal(),
                "engineering",
                new MockMultipartFile[]{file("one.md", "# One"), file("two.md", "# Two")},
                "zh-CN",
                null,
                null,
                null
        );

        assertEquals(2, result.totalItems());
        verify(objects, times(2)).put(any(), any());
        verify(store, times(2)).appendSource(any(), any(), any(), any());
        verify(objects, never()).delete(any());
    }

    @Test
    void rejectsDeclaredBatchSizeBeforeCreatingRun() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();
        ExtractionRunApplicationService service = service(store, objects, 5L);

        assertThrows(SourceSizeLimitExceededException.class, () -> service.create(
                principal(),
                "engineering",
                new MockMultipartFile[]{file("one.md", "123"), file("two.md", "456")},
                "zh-CN",
                null,
                null,
                null
        ));

        verify(store, never()).begin(any());
        verify(objects, never()).put(any(), any());
    }

    @Test
    void keepsOssObjectAndMarksStableFailureWhenDirectoryAppendFails() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();
        ExtractionRunApplicationService service = service(store, objects, 1_024L);
        doThrow(new IllegalStateException("database unavailable"))
                .when(store).appendSource(any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> service.create(
                principal(),
                "engineering",
                new MockMultipartFile[]{file("one.md", "# One")},
                "zh-CN",
                null,
                null,
                null
        ));

        verify(store).failCreation(any(), eq("SOURCE_UPLOAD_FAILED"), any());
        verify(objects, never()).delete(any());
    }

    @Test
    void refusesDownloadWhenOssIntegrityMetadataChanged() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();
        UUID runId = UUID.randomUUID();
        var run = snapshot(runId, 1);
        var item = run.items().getFirst();
        var asset = item.sourceAsset();
        when(store.find(principal().tenantId(), new KnowledgeSpaceId("engineering"), runId))
                .thenReturn(Optional.of(run));
        AtomicBoolean closed = new AtomicBoolean();
        var content = new ByteArrayInputStream(new byte[]{1}) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        when(objects.get(any())).thenReturn(Optional.of(new StoredObject(
                new StoredObjectMetadata(
                        new ObjectAddress(
                                asset.tenantId(),
                                asset.spaceId(),
                                asset.objectId()
                        ),
                        asset.storageId(),
                        asset.fileName(),
                        asset.mediaType(),
                        asset.contentLength(),
                        "1".repeat(64),
                        "etag",
                        Map.of(),
                        NOW
                ),
                content
        )));
        ExtractionRunApplicationService service = service(store, objects, 1_024L);

        assertThrows(ExtractionRunNotFoundException.class, () -> service.openSource(
                principal(),
                "engineering",
                runId,
                item.id()
        ));
        assertTrue(closed.get());
    }

    @Test
    void validatesAndCapturesTestConfigWithoutChangingTheSpaceConfiguration() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        ExtractionRunApplicationService service = service(
                store,
                objects,
                1_024L,
                capabilities
        );
        when(store.seal(any(), anyInt(), any())).thenAnswer(invocation ->
                snapshot(invocation.getArgument(0), 1)
        );
        var testConfig = testConfig();
        when(capabilities.processingContract(testConfig)).thenReturn(contract());

        service.create(
                principal(),
                "engineering",
                new MockMultipartFile[]{file("one.md", "# One")},
                "zh-CN",
                null,
                null,
                testConfig
        );

        verify(capabilities).processingContract(testConfig);
        ArgumentCaptor<ExtractionRunStore.BeginRequest> begin =
                ArgumentCaptor.forClass(ExtractionRunStore.BeginRequest.class);
        verify(store).begin(begin.capture());
        assertEquals(1L, begin.getValue().configVersion());
        assertEquals(
                512,
                begin.getValue().configSnapshot().chunker().maximumTokens()
        );
        assertEquals(
                ExtractionConfigSnapshots.capture(
                        testConfig,
                        contract(),
                        DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
                ),
                begin.getValue().configSnapshot()
        );
        assertNotEquals(
                ExtractionConfigSnapshots.capture(
                        processingConfig(),
                        contract(),
                        DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
                ).fingerprint(),
                begin.getValue().configSnapshot().fingerprint()
        );
    }

    @Test
    void rejectsUnavailableTestConfigBeforeCreatingRunOrWritingOss() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();
        DocumentProcessingCapabilities capabilities = mock(
                DocumentProcessingCapabilities.class
        );
        var testConfig = testConfig();
        when(capabilities.processingContract(testConfig)).thenThrow(
                new IllegalArgumentException("selected Tokenizer is unavailable")
        );
        ExtractionRunApplicationService service = service(
                store,
                objects,
                1_024L,
                capabilities
        );

        assertThrows(IllegalArgumentException.class, () -> service.create(
                principal(),
                "engineering",
                new MockMultipartFile[]{file("one.md", "# One")},
                "zh-CN",
                null,
                null,
                testConfig
        ));

        verify(store, never()).begin(any());
        verify(objects, never()).put(any(), any());
    }

    private static ExtractionRunApplicationService service(
            ExtractionRunStore store,
            ObjectStorage objects,
            long maximumTotalBytes
    ) {
        return service(
                store,
                objects,
                maximumTotalBytes,
                mock(DocumentProcessingCapabilities.class)
        );
    }

    private static ExtractionRunApplicationService service(
            ExtractionRunStore store,
            ObjectStorage objects,
            long maximumTotalBytes,
            DocumentProcessingCapabilities capabilities
    ) {
        var fileProperties = new FileIngestionProperties(
                1_024,
                4_096,
                10,
                100,
                10_000,
                100,
                20
        );
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        var config = processingConfig();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var runtime = new ExtractionRunService(
                store,
                objects,
                (tenantId, spaceId) -> config,
                clock
        );
        return new ExtractionRunApplicationService(
                runtime,
                new UploadedSourceReader(fileProperties, parsers),
                new ExtractionRunProperties(
                        10,
                        maximumTotalBytes,
                        50,
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(30)
                ),
                mock(ExtractionDatasetCatalog.class),
                capabilities
        );
    }

    private static ObjectStorage objectStorage() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.put(any(), any())).thenAnswer(invocation -> {
            ObjectWriteRequest request = invocation.getArgument(0);
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
        });
        return storage;
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile(
                "files",
                name,
                "text/markdown",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private static PrincipalContext principal() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }

    private static ExtractionRunStore.RunSnapshot snapshot(
            java.util.UUID runId,
            int itemCount
    ) {
        List<ExtractionRunStore.RunItem> items = java.util.stream.IntStream
                .range(0, itemCount)
                .mapToObj(index -> {
                    var source = new ExtractionRunStore.SourceAsset(
                            java.util.UUID.randomUUID(),
                            principal().tenantId(),
                            new KnowledgeSpaceId("engineering"),
                            "asset-" + index,
                            "storage-" + index,
                            "source-" + index + ".md",
                            "text/markdown",
                            1L,
                            "0".repeat(64),
                            NOW
                    );
                    return new ExtractionRunStore.RunItem(
                            java.util.UUID.randomUUID(),
                            source,
                            new ExtractionRunStore.PublicationAttributes(
                                    source.fileName(),
                                    source.fileName(),
                                    80
                            ),
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
                })
                .toList();
        return new ExtractionRunStore.RunSnapshot(
                runId,
                principal().tenantId(),
                new KnowledgeSpaceId("engineering"),
                ExtractionMode.TEST_ONLY,
                ExtractionRunStore.RunStatus.QUEUED,
                "zh-CN",
                1L,
                ExtractionConfigSnapshots.capture(
                        processingConfig(),
                        contract(),
                        DocumentProcessingContractFactory.IDENTITY_NORMALIZER_CONTRACT
                ),
                null,
                null,
                ExtractionGateStatus.NOT_EVALUATED,
                null,
                principal().principalId(),
                null,
                NOW,
                NOW,
                null,
                null,
                null,
                items
        );
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            processingConfig() {
        DocumentParserRegistry parsers = DocumentParserRegistry.standard();
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                principal().tenantId(),
                new KnowledgeSpaceId("engineering"),
                parsers.defaultParserSelections(),
                SpaceDocumentProcessingConfigStore.CleaningConfiguration.defaults(),
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        64,
                        128,
                        256,
                        16,
                        "{}"
                ),
                contract(),
                1L,
                principal().principalId(),
                NOW
        );
    }

    private static DocumentProcessingContract contract() {
        return DocumentProcessingContract.create(
                DocumentProcessingContractFactory.PIPELINE_CONTRACT,
                DocumentProcessingContractFactory.NORMALIZER_SCHEMA_CONTRACT,
                Map.of("text/markdown", "markdown-structure/v1"),
                "cleaner/v1",
                "structural/utf8-byte-budget/v1"
        );
    }

    private static SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig
            testConfig() {
        var active = processingConfig();
        return new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                active.tenantId(),
                active.spaceId(),
                active.parserSelections(),
                active.cleaning(),
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                        "STRUCTURAL",
                        "UTF8_BYTE_BUDGET",
                        64,
                        128,
                        512,
                        16,
                        "{}"
                ),
                0L,
                principal().principalId(),
                Instant.EPOCH
        );
    }
}
