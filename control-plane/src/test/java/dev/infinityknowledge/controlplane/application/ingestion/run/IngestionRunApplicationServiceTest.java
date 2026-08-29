package dev.infinityknowledge.controlplane.application.ingestion.run;

import dev.infinityknowledge.controlplane.api.document.ingestion.IngestionManifest;
import dev.infinityknowledge.controlplane.application.ingestion.UploadedSourceReader;
import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.controlplane.config.ingestion.ExtractionRunProperties;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.runtime.extraction.ExtractionRunService;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore;
import dev.infinityknowledge.spi.extraction.ExtractionRunStore.PublicationAttributes;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import dev.infinityknowledge.spi.objectstorage.StoredObjectMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证正式多文件入口的 Manifest 对齐、鉴权和不可变发布属性。 */
class IngestionRunApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-22T08:00:00Z");

    @Test
    void preservesOrderedPublicationAttributesAndUsesSafeFileNameAsBlankTitle() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();
        var service = service(store, objects);

        service.create(
                admin(),
                "engineering",
                new MockMultipartFile[]{
                        file("folder/one.md", "# One"),
                        file("two.md", "# Two")
                },
                new IngestionManifest(List.of(
                        new IngestionManifest.Item("manual/one", "运行手册", 95),
                        new IngestionManifest.Item("manual/two", "  ", null)
                )),
                "zh-CN"
        );

        var publications = ArgumentCaptor.forClass(PublicationAttributes.class);
        verify(store, times(2)).appendSource(
                any(), any(), publications.capture(), any()
        );
        assertEquals(
                new PublicationAttributes("manual/one", "运行手册", 95),
                publications.getAllValues().get(0)
        );
        assertEquals(
                new PublicationAttributes("manual/two", "two.md", 80),
                publications.getAllValues().get(1)
        );
        verify(objects, times(2)).put(any(), any());
    }

    @Test
    void rejectsManifestCountMismatchBeforeCreatingRun() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();

        assertThrows(IllegalArgumentException.class, () -> service(store, objects).create(
                admin(),
                "engineering",
                new MockMultipartFile[]{file("one.md", "# One")},
                new IngestionManifest(List.of()),
                "zh-CN"
        ));

        verify(store, never()).begin(any());
        verify(objects, never()).put(any(), any());
    }

    @Test
    void rejectsDuplicateExternalIdsBeforeCreatingRun() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();

        assertThrows(IllegalArgumentException.class, () -> service(store, objects).create(
                admin(),
                "engineering",
                new MockMultipartFile[]{file("one.md", "# One"), file("two.md", "# Two")},
                new IngestionManifest(List.of(
                        new IngestionManifest.Item("same", "One", 80),
                        new IngestionManifest.Item("same", "Two", 80)
                )),
                "zh-CN"
        ));

        verify(store, never()).begin(any());
        verify(objects, never()).put(any(), any());
    }

    @Test
    void rejectsNonAdminBeforeReadingOrRetainingSources() {
        ExtractionRunStore store = mock(ExtractionRunStore.class);
        ObjectStorage objects = objectStorage();

        assertThrows(AccessDeniedException.class, () -> service(store, objects).create(
                reader(),
                "engineering",
                new MockMultipartFile[]{file("one.md", "# One")},
                new IngestionManifest(List.of(
                        new IngestionManifest.Item("one", "One", 80)
                )),
                "zh-CN"
        ));

        verify(store, never()).begin(any());
        verify(objects, never()).put(any(), any());
    }

    private static IngestionRunApplicationService service(
            ExtractionRunStore store,
            ObjectStorage objects
    ) {
        var parsers = DocumentParserRegistry.standard();
        var config = new SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig(
                admin().tenantId(),
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
                DocumentProcessingContract.create(
                        "pipeline-v7",
                        "normalizer-schema-v2",
                        parsers.selectedParserContracts(
                                parsers.defaultParserSelections()
                        ),
                        "cleaner-v1",
                        "chunker-v1"
                ),
                1L,
                admin().principalId(),
                NOW
        );
        var runtime = new ExtractionRunService(
                store,
                objects,
                (tenantId, spaceId) -> config,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new IngestionRunApplicationService(
                runtime,
                new UploadedSourceReader(fileProperties(), parsers),
                new ExtractionRunProperties(
                        10,
                        4_096,
                        50,
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(30)
                )
        );
    }

    private static ObjectStorage objectStorage() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.put(any(ObjectWriteRequest.class), any())).thenAnswer(invocation -> {
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

    private static FileIngestionProperties fileProperties() {
        return new FileIngestionProperties(
                1_024,
                4_096,
                10,
                100,
                10_000,
                100,
                20
        );
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile(
                "files",
                name,
                "text/markdown",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private static PrincipalContext admin() {
        return principal(Set.of("knowledge-admin"));
    }

    private static PrincipalContext reader() {
        return principal(Set.of("knowledge-reader"));
    }

    private static PrincipalContext principal(Set<String> roles) {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("principal-a"),
                roles,
                Set.of(),
                false
        );
    }
}
