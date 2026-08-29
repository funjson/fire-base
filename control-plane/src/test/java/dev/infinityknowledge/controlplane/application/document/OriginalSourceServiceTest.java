package dev.infinityknowledge.controlplane.application.document;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.SourceObjectReference;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.objectstorage.DocumentSourceObject;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.SourceObjectCatalog;
import dev.infinityknowledge.spi.objectstorage.StoredObject;
import dev.infinityknowledge.spi.objectstorage.StoredObjectMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies tenant-bound catalog lookup and document-level ACL enforcement. */
class OriginalSourceServiceTest {

    @Test
    void opensAnAuthorizedOriginalSourceByDocumentId() throws Exception {
        byte[] content = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DocumentSourceObject source = source(content.length);
        SourceObjectCatalog catalog = mock(SourceObjectCatalog.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(catalog.findActive(any(), any())).thenReturn(Optional.of(source));
        when(storage.get(any())).thenReturn(Optional.of(new StoredObject(
                metadata(source, content.length),
                new ByteArrayInputStream(content)
        )));
        AccessPolicy policy = (principal, spaces) -> AccessScope.all(principal.tenantId(), spaces);
        var service = new OriginalSourceService(catalog, storage, policy);

        try (var download = service.open(principal(), source.documentId().value())) {
            assertArrayEquals(content, download.stored().content().readAllBytes());
        }
    }

    @Test
    void rejectsAUserWithoutDocumentAcl() {
        DocumentSourceObject source = source(5);
        SourceObjectCatalog catalog = mock(SourceObjectCatalog.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(catalog.findActive(any(), any())).thenReturn(Optional.of(source));
        AccessPolicy denied = (principal, spaces) -> AccessScope.denyAll(principal.tenantId());
        var service = new OriginalSourceService(catalog, storage, denied);

        assertThrows(
                KnowledgeAccessDeniedException.class,
                () -> service.metadata(principal(), source.documentId().value())
        );
    }

    @Test
    void letsTenantAdminInspectARetainedInactiveSource() {
        DocumentSourceObject source = new DocumentSourceObject(
                source(5).documentId(),
                new KnowledgeSpaceId("engineering"),
                DocumentStatus.ARCHIVED,
                source(5).sourceObject()
        );
        SourceObjectCatalog catalog = mock(SourceObjectCatalog.class);
        when(catalog.findRetained(any(), any())).thenReturn(Optional.of(source));
        var service = new OriginalSourceService(
                catalog,
                mock(ObjectStorage.class),
                (principal, spaces) -> AccessScope.denyAll(principal.tenantId())
        );

        service.metadata(admin(), source.documentId().value());

        verify(catalog).findRetained(any(), any());
        verify(catalog, never()).findActive(any(), any());
    }

    private static DocumentSourceObject source(long contentLength) {
        return new DocumentSourceObject(
                new DocumentId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                new KnowledgeSpaceId("engineering"),
                DocumentStatus.ACTIVE,
                new SourceObjectReference(
                        UUID.fromString("20000000-0000-0000-0000-000000000001"),
                        "source-object",
                        "handbook.txt",
                        "text/plain",
                        contentLength,
                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                        Instant.parse("2026-08-11T00:00:00Z")
                )
        );
    }

    private static StoredObjectMetadata metadata(
            DocumentSourceObject source,
            long contentLength
    ) {
        var reference = source.sourceObject();
        return new StoredObjectMetadata(
                new ObjectAddress(new TenantId("tenant-a"), source.spaceId(), reference.storageId()),
                reference.storageId(),
                reference.originalFileName(),
                reference.mediaType(),
                contentLength,
                reference.checksumSha256(),
                "etag",
                Map.of(),
                reference.storedAt()
        );
    }

    private static PrincipalContext principal() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("reader"),
                Set.of(),
                Set.of(),
                false
        );
    }

    private static PrincipalContext admin() {
        return new PrincipalContext(
                new TenantId("tenant-a"),
                new PrincipalId("admin"),
                Set.of("knowledge-admin"),
                Set.of(),
                false
        );
    }
}
