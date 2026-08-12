package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.controlplane.config.IngestionProperties;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.ingestion.HeadingAwareChunker;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import dev.infinityknowledge.spi.objectstorage.StoredObjectMetadata;
import dev.infinityknowledge.spi.vector.VectorProjectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies original-object compensation and transactional source references. */
class FileIngestionServiceTest {

    @Test
    void storesParsesAndPublishesTheSameBoundedBytes() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        ObjectStorage storage = storage();
        when(catalog.findDocumentId(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(writer.write(any())).thenAnswer(invocation -> {
            KnowledgeWriteBatch batch = invocation.getArgument(0);
            return new KnowledgeWriteResult(
                    batch.document().id(),
                    batch.revision().id(),
                    true,
                    batch.chunks().size(),
                    true
            );
        });

        var result = service(catalog, writer, storage).ingest(
                principal(),
                htmlFile(),
                "engineering",
                "handbook/oncall",
                "On-call handbook",
                "zh-CN",
                80
        );

        ArgumentCaptor<KnowledgeWriteBatch> batch = ArgumentCaptor.forClass(KnowledgeWriteBatch.class);
        verify(writer).write(batch.capture());
        assertEquals("text/html", batch.getValue().revision().mediaType());
        assertEquals(
                batch.getValue().revision().contentHash(),
                batch.getValue().sourceObject().checksumSha256()
        );
        assertEquals("oncall.html", result.originalFileName());
        assertTrue(result.elementCount() >= 2);
        verify(storage, never()).delete(any());
    }

    @Test
    void deletesTheUploadedObjectWhenTheKnowledgeTransactionFails() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        ObjectStorage storage = storage();
        when(catalog.findDocumentId(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(writer.write(any())).thenThrow(new IllegalStateException("transaction failed"));

        assertThrows(IllegalStateException.class, () -> service(catalog, writer, storage).ingest(
                principal(), htmlFile(), "engineering", "handbook/oncall", null, "zh-CN", 80
        ));

        verify(storage).delete(any(ObjectAddress.class));
    }

    @Test
    void deletesAConcurrentDuplicateObjectThatWasNotAccepted() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        ObjectStorage storage = storage();
        when(catalog.findDocumentId(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(writer.write(any())).thenAnswer(invocation -> {
            KnowledgeWriteBatch batch = invocation.getArgument(0);
            return new KnowledgeWriteResult(
                    batch.document().id(),
                    batch.revision().id(),
                    false,
                    batch.chunks().size(),
                    false
            );
        });

        service(catalog, writer, storage).ingest(
                principal(), htmlFile(), "engineering", "handbook/oncall", null, "zh-CN", 80
        );

        verify(storage).delete(any(ObjectAddress.class));
    }

    private static ObjectStorage storage() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.put(any(), any())).thenAnswer(invocation -> {
            ObjectWriteRequest request = invocation.getArgument(0);
            return new StoredObjectMetadata(
                    request.address(),
                    request.address().objectId(),
                    request.originalFileName(),
                    request.mediaType(),
                    request.contentLength(),
                    request.checksumSha256(),
                    "etag",
                    Map.of(),
                    Instant.parse("2026-08-11T00:00:00Z")
            );
        });
        return storage;
    }

    private static FileIngestionService service(
            KnowledgeCatalog catalog,
            KnowledgeWriter writer,
            ObjectStorage storage
    ) {
        var beanFactory = new StaticListableBeanFactory();
        return new FileIngestionService(
                catalog,
                writer,
                storage,
                DocumentParserRegistry.standard(),
                new HeadingAwareChunker(512, 1_024),
                new IngestionProperties(512, 1_024, List.of("http", "https", "obsidian")),
                new FileIngestionProperties(
                        1_048_576,
                        4_194_304,
                        50,
                        1_000,
                        100_000,
                        1_000,
                        100
                ),
                beanFactory.getBeanProvider(VectorProjectionService.class),
                Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static MockMultipartFile htmlFile() {
        return new MockMultipartFile(
                "file",
                "..\\oncall.html",
                "text/html; charset=UTF-8",
                "<html><head><title>On-call</title></head><body><h1>Incident</h1><p>Escalate safely.</p></body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)
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
}
