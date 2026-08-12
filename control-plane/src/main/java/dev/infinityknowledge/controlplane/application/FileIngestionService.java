package dev.infinityknowledge.controlplane.application;

import dev.infinityknowledge.controlplane.api.FileDocumentResponse;
import dev.infinityknowledge.controlplane.config.FileIngestionProperties;
import dev.infinityknowledge.controlplane.config.IngestionProperties;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentRevision;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceObjectReference;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.HeadingAwareChunker;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.ingestion.KnowledgeCatalog;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriteBatch;
import dev.infinityknowledge.spi.ingestion.KnowledgeWriter;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import dev.infinityknowledge.spi.vector.VectorProjectionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes bounded rich-document uploads while retaining their immutable original bytes.
 */
@Service
public final class FileIngestionService {

    private static final Map<String, String> MEDIA_TYPES_BY_EXTENSION = Map.of(
            ".txt", "text/plain",
            ".text", "text/plain",
            ".log", "text/plain",
            ".html", "text/html",
            ".htm", "text/html",
            ".xhtml", "application/xhtml+xml",
            ".pdf", "application/pdf",
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final KnowledgeCatalog catalog;
    private final KnowledgeWriter writer;
    private final ObjectStorage objectStorage;
    private final DocumentParserRegistry parsers;
    private final HeadingAwareChunker chunker;
    private final IngestionProperties ingestionProperties;
    private final FileIngestionProperties fileProperties;
    private final VectorProjectionService vectorProjectionService;
    private final Clock clock;

    /** Creates the synchronous, resource-bounded upload service. */
    public FileIngestionService(
            KnowledgeCatalog catalog,
            KnowledgeWriter writer,
            ObjectStorage objectStorage,
            DocumentParserRegistry parsers,
            HeadingAwareChunker chunker,
            IngestionProperties ingestionProperties,
            FileIngestionProperties fileProperties,
            ObjectProvider<VectorProjectionService> vectorProjectionService,
            Clock clock
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.parsers = Objects.requireNonNull(parsers, "parsers must not be null");
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
        this.ingestionProperties = Objects.requireNonNull(
                ingestionProperties,
                "ingestionProperties must not be null"
        );
        this.fileProperties = Objects.requireNonNull(fileProperties, "fileProperties must not be null");
        this.vectorProjectionService = vectorProjectionService.getIfAvailable();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Stores, parses and transactionally publishes one upload.
     *
     * <p>The multipart stream is read exactly once into a bounded byte buffer. That same
     * immutable buffer is used for object storage and parsing, so the persisted checksum,
     * parser input and revision fingerprint cannot diverge.</p>
     */
    public FileDocumentResponse ingest(
            PrincipalContext principal,
            MultipartFile file,
            String requestedSpaceId,
            String externalId,
            String requestedTitle,
            String language,
            int authority
    ) {
        requireAdmin(principal);
        Objects.requireNonNull(file, "file must not be null");
        if (file.isEmpty()) {
            throw new IllegalArgumentException("file must not be empty");
        }
        var spaceId = new KnowledgeSpaceId(requestedSpaceId);
        String fileName = safeFileName(file.getOriginalFilename());
        String mediaType = normalizedMediaType(file.getContentType(), fileName);
        String normalizedLanguage = normalizeLanguageTag(language);
        String normalizedExternalId = required(externalId, "externalId", 512);
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? fileName
                : required(requestedTitle, "title", 512);
        BufferedSource buffered = readBounded(file);
        byte[] source = buffered.bytes();
        String checksum = buffered.checksumSha256();
        var sourceDescriptor = new SourceDescriptor(
                apiUploadConnectorId(spaceId),
                SourceType.UPLOAD,
                normalizedExternalId,
                internalSourceUri(spaceId, normalizedExternalId),
                Map.of("originalFileName", fileName, "mediaType", mediaType)
        );
        DocumentId documentId = catalog.findDocumentId(
                principal.tenantId(),
                spaceId,
                sourceDescriptor.connectorId(),
                sourceDescriptor.externalId()
        ).orElseGet(() -> documentId(principal, spaceId, sourceDescriptor));
        String processorVersion = processorVersion(parsers.parserContract(mediaType, fileName));
        UUID revisionId = revisionId(
                documentId,
                checksum,
                mediaType,
                normalizedLanguage,
                processorVersion
        );
        var address = new ObjectAddress(
                principal.tenantId(),
                spaceId,
                UUID.randomUUID().toString()
        );
        var stored = objectStorage.put(
                new ObjectWriteRequest(
                        address,
                        fileName,
                        mediaType,
                        source.length,
                        checksum,
                        Map.of("source", "api-upload")
                ),
                new ByteArrayInputStream(source)
        );
        boolean retained = false;
        try {
            var parsed = parsers.parse(
                    revisionId,
                    mediaType,
                    fileName,
                    source,
                    fileProperties.limits()
            );
            if (parsed.elements().isEmpty()) {
                throw new DocumentParseException("document did not contain indexable text");
            }
            var chunks = chunker.chunk(
                    principal.tenantId(),
                    spaceId,
                    documentId,
                    revisionId,
                    parsed.elements()
            );
            if (chunks.isEmpty()) {
                throw new DocumentParseException("document did not produce indexable chunks");
            }
            var now = clock.instant();
            var document = new KnowledgeDocument(
                    documentId,
                    principal.tenantId(),
                    spaceId,
                    title,
                    sourceDescriptor,
                    DocumentStatus.ACTIVE,
                    authority,
                    Map.of(
                            "originalFileName", fileName,
                            "mediaType", mediaType,
                            "parser", parsed.parserId()
                    ),
                    now,
                    now
            );
            var revision = new DocumentRevision(
                    revisionId,
                    documentId,
                    checksum,
                    mediaType,
                    normalizedLanguage,
                    processorVersion,
                    now
            );
            var sourceObject = new SourceObjectReference(
                    revisionId,
                    stored.storageId(),
                    fileName,
                    mediaType,
                    source.length,
                    checksum,
                    stored.storedAt()
            );
            var result = writer.write(new KnowledgeWriteBatch(
                    document,
                    revision,
                    parsed.elements(),
                    chunks,
                    sourceObject
            ));
            retained = result.sourceObjectAccepted();
            if (!retained) {
                objectStorage.delete(address);
            }
            String vectorStatus = vectorProjectionService == null
                    ? "SKIPPED"
                    : result.changed() ? "QUEUED" : "UNCHANGED";
            List<String> warnings = vectorProjectionService == null
                    ? List.of("VECTOR_PROJECTION_DISABLED")
                    : List.of();
            if (!result.sourceObjectAccepted()) {
                warnings = java.util.stream.Stream.concat(
                                warnings.stream(),
                                java.util.stream.Stream.of("ORIGINAL_SOURCE_UNCHANGED")
                        )
                        .toList();
            }
            return new FileDocumentResponse(
                    result.documentId().value(),
                    result.revisionId(),
                    result.changed(),
                    parsed.elements().size(),
                    result.chunkCount(),
                    fileName,
                    mediaType,
                    source.length,
                    vectorStatus,
                    warnings
            );
        } catch (RuntimeException failure) {
            if (!retained) {
                compensateDelete(address, failure);
            }
            throw failure;
        }
    }

    private BufferedSource readBounded(MultipartFile file) {
        long declared = file.getSize();
        int maximum = fileProperties.maximumSourceBytes();
        if (declared > maximum) {
            throw new IllegalArgumentException("file exceeds maximumSourceBytes");
        }
        try (InputStream input = file.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     declared > 0 ? (int) Math.min(declared, maximum) : 8_192
             )) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximum) {
                    throw new IllegalArgumentException("file exceeds maximumSourceBytes");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            return new BufferedSource(
                    output.toByteArray(),
                    HexFormat.of().formatHex(digest.digest())
            );
        } catch (IOException failure) {
            throw new IllegalArgumentException("failed to read uploaded file", failure);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private void compensateDelete(ObjectAddress address, RuntimeException original) {
        try {
            objectStorage.delete(address);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private String processorVersion(String parserContract) {
        return String.join(
                ":",
                "rich-v1",
                sha256(parserContract.getBytes(StandardCharsets.UTF_8)).substring(0, 16),
                HeadingAwareChunker.VERSION,
                Integer.toString(ingestionProperties.targetChunkCharacters()),
                Integer.toString(ingestionProperties.maximumChunkCharacters())
        );
    }

    private static String normalizedMediaType(String requested, String fileName) {
        String extension = extension(fileName);
        String inferred = MEDIA_TYPES_BY_EXTENSION.get(extension);
        String normalized = requested == null
                ? ""
                : requested.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "application/octet-stream".equals(normalized)) {
            if (inferred == null) {
                throw new IllegalArgumentException("unsupported document media type");
            }
            return inferred;
        }
        if (inferred != null && !compatible(normalized, inferred)) {
            throw new IllegalArgumentException("file extension does not match media type");
        }
        if (!MEDIA_TYPES_BY_EXTENSION.containsValue(normalized)) {
            throw new IllegalArgumentException("unsupported document media type");
        }
        return normalized;
    }

    private static boolean compatible(String supplied, String inferred) {
        return supplied.equals(inferred)
                || ("text/html".equals(inferred) && "application/xhtml+xml".equals(supplied));
    }

    private static String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index).toLowerCase(Locale.ROOT);
    }

    private static String safeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("original file name is required");
        }
        String name = rawName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)
                || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("original file name is invalid");
        }
        return required(name, "originalFileName", 512);
    }

    private static String internalSourceUri(KnowledgeSpaceId spaceId, String externalId) {
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        return "upload://" + encoder.encodeToString(spaceId.value().getBytes(StandardCharsets.UTF_8))
                + "/" + encoder.encodeToString(externalId.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID revisionId(
            DocumentId documentId,
            String contentHash,
            String mediaType,
            String language,
            String processorVersion
    ) {
        String identity = String.join(
                "\u001F",
                documentId.value().toString(),
                contentHash,
                mediaType,
                language,
                processorVersion
        );
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static DocumentId documentId(
            PrincipalContext principal,
            KnowledgeSpaceId spaceId,
            SourceDescriptor source
    ) {
        String identity = String.join(
                ":",
                principal.tenantId().value(),
                spaceId.value(),
                source.connectorId(),
                source.externalId()
        );
        return new DocumentId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
    }

    private static String normalizeLanguageTag(String language) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        try {
            return new Locale.Builder().setLanguageTag(language.strip()).build().toLanguageTag();
        } catch (IllformedLocaleException invalidLanguage) {
            throw new IllegalArgumentException("language must be a well-formed BCP 47 tag", invalidLanguage);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String required(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return normalized;
    }

    private static String apiUploadConnectorId(KnowledgeSpaceId spaceId) {
        return "api-upload:" + spaceId.value();
    }

    private static void requireAdmin(PrincipalContext principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (!principal.systemPrincipal() && !principal.roleIds().contains("knowledge-admin")) {
            throw new AccessDeniedException("knowledge-admin role is required");
        }
    }

    private record BufferedSource(byte[] bytes, String checksumSha256) {
    }
}
