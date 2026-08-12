package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.MarkdownDocumentRequest;
import dev.infinityknowledge.controlplane.config.IngestionProperties;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.HeadingAwareChunker;
import dev.infinityknowledge.ingestion.MarkdownElementParser;
import dev.infinityknowledge.spi.connector.SourceRecord;
import dev.infinityknowledge.spi.connector.ConnectorStateStore;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteResult;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.vector.VectorProjectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Markdown API 的来源身份在进入持久化前已经包含空间边界。
 */
class MarkdownIngestionServiceTest {

    @Test
    void createsIndependentApiDocumentIdentityForEachSpace() {
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
        var service = service(catalog, writer);

        service.ingest(principal(), request("space-a"));
        service.ingest(principal(), request("space-b"));

        ArgumentCaptor<KnowledgeWriteBatch> batches =
                ArgumentCaptor.forClass(KnowledgeWriteBatch.class);
        verify(writer, times(2)).write(batches.capture());
        var first = batches.getAllValues().get(0).document();
        var second = batches.getAllValues().get(1).document();
        assertNotEquals(first.id(), second.id());
        assertEquals("api-upload:space-a", first.source().connectorId());
        assertEquals("api-upload:space-b", second.source().connectorId());
        assertEquals("space-a", first.spaceId().value());
        assertEquals("space-b", second.spaceId().value());
    }

    @Test
    void rejectsSourceSchemeBeforeWritingKnowledge() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        var service = service(catalog, writer);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.ingest(
                        principal(),
                        request("space-a", "javascript:alert(1)")
                )
        );
        verify(writer, never()).write(any());
    }

    @Test
    void includesLanguageInTheDeterministicRevisionIdentity() {
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
        var service = service(catalog, writer);

        service.ingest(principal(), request("space-a", "https://example.test/a", "zh-CN"));
        service.ingest(principal(), request("space-a", "https://example.test/a", "en-US"));

        ArgumentCaptor<KnowledgeWriteBatch> batches =
                ArgumentCaptor.forClass(KnowledgeWriteBatch.class);
        verify(writer, times(2)).write(batches.capture());
        assertNotEquals(
                batches.getAllValues().get(0).revision().id(),
                batches.getAllValues().get(1).revision().id()
        );
    }

    @Test
    void rejectsMalformedLanguageBeforeWritingKnowledge() {
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        KnowledgeWriter writer = mock(KnowledgeWriter.class);
        var service = service(catalog, writer);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.ingest(
                        principal(),
                        request(
                                "space-a",
                                "https://example.test/a",
                                "not_a_language_tag"
                        )
                )
        );
        verify(writer, never()).write(any());
    }

    @Test
    void canonicalizesMarkdownMediaTypeInTheRevisionIdentity() {
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
        var service = service(catalog, writer);
        var source = new SourceDescriptor(
                "obsidian:engineering",
                SourceType.OBSIDIAN,
                "shared.md",
                "obsidian://open?vault=engineering&file=shared.md",
                Map.of()
        );
        Instant modifiedAt = Instant.parse("2026-08-03T00:00:00Z");

        service.ingestSourceRecord(
                lease(),
                new KnowledgeSpaceId("space-a"),
                new SourceRecord(
                        source, "Shared", "text/markdown", "# Shared",
                        "a".repeat(64), Map.of("language", "zh-CN"), modifiedAt, false
                ),
                80
        );
        service.ingestSourceRecord(
                lease(),
                new KnowledgeSpaceId("space-a"),
                new SourceRecord(
                        source, "Shared", "TEXT/MARKDOWN", "# Shared",
                        "a".repeat(64), Map.of("language", "zh-CN"), modifiedAt, false
                ),
                80
        );

        ArgumentCaptor<KnowledgeWriteBatch> batches =
                ArgumentCaptor.forClass(KnowledgeWriteBatch.class);
        verify(writer, times(2)).write(batches.capture());
        assertEquals(
                batches.getAllValues().get(0).revision().id(),
                batches.getAllValues().get(1).revision().id()
        );
        assertEquals(
                "text/markdown",
                batches.getAllValues().get(1).revision().mediaType()
        );
        assertEquals("worker-a", batches.getAllValues().getFirst()
                .connectorWriteFence().leaseOwner());
    }

    private static MarkdownIngestionService service(
            KnowledgeCatalog catalog,
            KnowledgeWriter writer
    ) {
        var beanFactory = new StaticListableBeanFactory();
        return new MarkdownIngestionService(
                catalog,
                writer,
                beanFactory.getBeanProvider(VectorProjectionService.class),
                new MarkdownElementParser(),
                new HeadingAwareChunker(512, 1_024),
                new IngestionProperties(
                        512,
                        1_024,
                        List.of("http", "https", "obsidian")
                ),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC)
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

    private static ConnectorStateStore.SynchronizationLease lease() {
        return new ConnectorStateStore.SynchronizationLease(
                UUID.randomUUID(),
                principal(),
                "obsidian:engineering",
                UUID.randomUUID(),
                dev.infinityknowledge.spi.connector.ConnectorCursor.initial(),
                0,
                0,
                "worker-a",
                3,
                Instant.parse("2026-08-03T00:02:00Z")
        );
    }

    private static MarkdownDocumentRequest request(String spaceId) {
        return request(spaceId, "https://example.invalid/shared.md");
    }

    private static MarkdownDocumentRequest request(
            String spaceId,
            String sourceUri
    ) {
        return request(spaceId, sourceUri, "zh-CN");
    }

    private static MarkdownDocumentRequest request(
            String spaceId,
            String sourceUri,
            String language
    ) {
        return new MarkdownDocumentRequest(
                spaceId,
                "shared.md",
                "Shared",
                sourceUri,
                language,
                80,
                "# Shared\n\nSpace scoped content.",
                Map.of()
        );
    }
}
