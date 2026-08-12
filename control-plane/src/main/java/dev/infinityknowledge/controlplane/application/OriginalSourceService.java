package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.spi.access.AccessPolicy;
import dev.infinityknowledge.spi.access.KnowledgeAccessDeniedException;
import dev.infinityknowledge.spi.objectstorage.DocumentSourceObject;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.SourceObjectCatalog;
import dev.infinityknowledge.spi.objectstorage.StoredObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Resolves and authorizes original-source metadata and content. */
@Service
public final class OriginalSourceService {

    private final SourceObjectCatalog catalog;
    private final ObjectStorage objectStorage;
    private final AccessPolicy accessPolicy;

    /** Creates the tenant-scoped source service. */
    public OriginalSourceService(
            SourceObjectCatalog catalog,
            ObjectStorage objectStorage,
            AccessPolicy accessPolicy
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    /** Returns safe metadata; tenant admins may inspect retained inactive documents. */
    public DocumentSourceObject metadata(PrincipalContext principal, UUID documentId) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        boolean administrator = principal.systemPrincipal()
                || principal.roleIds().contains("knowledge-admin");
        var document = (administrator
                ? catalog.findRetained(principal.tenantId(), new DocumentId(documentId))
                : catalog.findActive(principal.tenantId(), new DocumentId(documentId)))
                .orElseThrow(KnowledgeDocumentNotFoundException::new);
        authorize(principal, document);
        return document;
    }

    /** Opens the authorized retained source and verifies persisted integrity metadata. */
    public SourceDownload open(PrincipalContext principal, UUID documentId) {
        DocumentSourceObject source = metadata(principal, documentId);
        var reference = source.sourceObject();
        var address = new ObjectAddress(
                principal.tenantId(),
                source.spaceId(),
                reference.storageId()
        );
        StoredObject stored = objectStorage.get(address)
                .orElseThrow(KnowledgeDocumentNotFoundException::new);
        try {
            if (stored.metadata().contentLength() != reference.contentLength()
                    || !stored.metadata().checksumSha256().equals(reference.checksumSha256())) {
                throw new IllegalStateException("stored source integrity metadata does not match catalog");
            }
            return new SourceDownload(source, stored);
        } catch (RuntimeException failure) {
            try {
                stored.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void authorize(PrincipalContext principal, DocumentSourceObject document) {
        if (principal.systemPrincipal() || principal.roleIds().contains("knowledge-admin")) {
            return;
        }
        var scope = accessPolicy.resolve(principal, Set.of(document.spaceId()));
        if (!principal.tenantId().equals(scope.tenantId())) {
            throw new SecurityException("access policy returned a different tenant");
        }
        if (!scope.allowsSpace(document.spaceId())
                || !scope.allowsDocument(document.documentId().value().toString())) {
            throw new KnowledgeAccessDeniedException("knowledge document is not accessible");
        }
    }

    /** Closeable content response owned by the HTTP streaming layer. */
    public record SourceDownload(
            DocumentSourceObject source,
            StoredObject stored
    ) implements AutoCloseable {

        /** Rejects incomplete download values. */
        public SourceDownload {
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(stored, "stored must not be null");
        }

        @Override
        public void close() throws IOException {
            stored.close();
        }
    }
}
