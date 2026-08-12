package dev.infinityknowledge.store.minio;

import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import dev.infinityknowledge.spi.objectstorage.StoredObject;
import dev.infinityknowledge.spi.objectstorage.StoredObjectMetadata;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MinIO implementation of the original-source object storage port.
 *
 * <p>Every physical key contains encoded tenant and space segments. File names are
 * metadata only, so a malicious name can never escape its logical scope.</p>
 */
public final class MinioObjectStorage implements ObjectStorage {

    private static final String SHA256 = "sha256";
    private static final String ORIGINAL_FILE_NAME = "original-filename-b64";
    private static final String ATTRIBUTE_PREFIX = "attribute-";

    private final MinioClient client;
    private final MinioObjectStorageConfig config;
    private final AtomicBoolean bucketReady = new AtomicBoolean();

    /** Creates an adapter around an externally configured MinIO client. */
    public MinioObjectStorage(MinioClient client, MinioObjectStorageConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public StoredObjectMetadata put(ObjectWriteRequest request, InputStream content) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (request.contentLength() > config.maximumObjectBytes()) {
            throw new IllegalArgumentException("source object exceeds maximumObjectBytes");
        }
        ensureBucket();
        String key = MinioObjectKey.from(request.address());
        Map<String, String> userMetadata = toUserMetadata(request);
        try {
            var response = client.putObject(PutObjectArgs.builder()
                    .bucket(config.bucketName())
                    .object(key)
                    .stream(content, request.contentLength(), -1)
                    .contentType(request.mediaType())
                    .userMetadata(userMetadata)
                    .build());
            return new StoredObjectMetadata(
                    request.address(),
                    request.address().objectId(),
                    request.originalFileName(),
                    request.mediaType(),
                    request.contentLength(),
                    request.checksumSha256(),
                    response.etag(),
                    request.attributes(),
                    Instant.now()
            );
        } catch (Exception failure) {
            throw failure("failed to store source object", failure);
        }
    }

    @Override
    public Optional<StoredObjectMetadata> head(ObjectAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        ensureBucket();
        try {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder()
                    .bucket(config.bucketName())
                    .object(MinioObjectKey.from(address))
                    .build());
            return Optional.of(toMetadata(address, response));
        } catch (Exception failure) {
            if (isMissing(failure)) {
                return Optional.empty();
            }
            throw failure("failed to inspect source object", failure);
        }
    }

    @Override
    public Optional<StoredObject> get(ObjectAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        Optional<StoredObjectMetadata> metadata = head(address);
        if (metadata.isEmpty()) {
            return Optional.empty();
        }
        try {
            GetObjectResponse content = client.getObject(GetObjectArgs.builder()
                    .bucket(config.bucketName())
                    .object(MinioObjectKey.from(address))
                    .build());
            return Optional.of(new StoredObject(metadata.orElseThrow(), content));
        } catch (Exception failure) {
            if (isMissing(failure)) {
                return Optional.empty();
            }
            throw failure("failed to read source object", failure);
        }
    }

    @Override
    public void delete(ObjectAddress address) {
        Objects.requireNonNull(address, "address must not be null");
        ensureBucket();
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(config.bucketName())
                    .object(MinioObjectKey.from(address))
                    .build());
        } catch (Exception failure) {
            throw failure("failed to delete source object", failure);
        }
    }

    private void ensureBucket() {
        if (!config.createBucket() || bucketReady.get()) {
            return;
        }
        synchronized (bucketReady) {
            if (bucketReady.get()) {
                return;
            }
            try {
                BucketExistsArgs exists = BucketExistsArgs.builder()
                        .bucket(config.bucketName())
                        .build();
                if (!client.bucketExists(exists)) {
                    try {
                        client.makeBucket(MakeBucketArgs.builder()
                                .bucket(config.bucketName())
                                .build());
                    } catch (Exception creationFailure) {
                        if (!client.bucketExists(exists)) {
                            throw creationFailure;
                        }
                    }
                }
                bucketReady.set(true);
            } catch (Exception failure) {
                throw failure("failed to prepare source bucket", failure);
            }
        }
    }

    private StoredObjectMetadata toMetadata(
            ObjectAddress address,
            StatObjectResponse response
    ) {
        Map<String, String> userMetadata = normalizeMetadata(response.userMetadata());
        String originalFileName = decodeFileName(required(userMetadata, ORIGINAL_FILE_NAME));
        Map<String, String> attributes = new LinkedHashMap<>();
        userMetadata.forEach((key, value) -> {
            if (key.startsWith(ATTRIBUTE_PREFIX)) {
                attributes.put(key.substring(ATTRIBUTE_PREFIX.length()), value);
            }
        });
        return new StoredObjectMetadata(
                address,
                address.objectId(),
                originalFileName,
                response.contentType(),
                response.size(),
                required(userMetadata, SHA256),
                response.etag(),
                attributes,
                response.lastModified().toInstant()
        );
    }

    private static Map<String, String> toUserMetadata(ObjectWriteRequest request) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(SHA256, request.checksumSha256());
        metadata.put(
                ORIGINAL_FILE_NAME,
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        request.originalFileName().getBytes(StandardCharsets.UTF_8)
                )
        );
        request.attributes().forEach((key, value) -> metadata.put(ATTRIBUTE_PREFIX + key, value));
        return Map.copyOf(metadata);
    }

    private static Map<String, String> normalizeMetadata(Map<String, String> metadata) {
        Map<String, String> normalized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (normalizedKey.startsWith("x-amz-meta-")) {
                normalizedKey = normalizedKey.substring("x-amz-meta-".length());
            }
            normalized.put(normalizedKey, value);
        });
        return Map.copyOf(normalized);
    }

    private static String decodeFileName(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidMetadata) {
            throw new IllegalStateException("stored file-name metadata is invalid", invalidMetadata);
        }
    }

    private static String required(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("stored object omitted required integrity metadata");
        }
        return value;
    }

    private static boolean isMissing(Exception failure) {
        return failure instanceof ErrorResponseException response
                && ("NoSuchKey".equals(response.errorResponse().code())
                || "NoSuchObject".equals(response.errorResponse().code()));
    }

    private static MinioObjectStoreException failure(String message, Exception cause) {
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new MinioObjectStoreException(message, cause);
    }
}
