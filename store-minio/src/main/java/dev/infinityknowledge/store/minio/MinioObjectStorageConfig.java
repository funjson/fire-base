package dev.infinityknowledge.store.minio;

import java.util.Objects;

/**
 * Immutable MinIO bucket and upload-budget settings.
 *
 * @param bucketName bucket containing retained original sources
 * @param maximumObjectBytes maximum accepted object size
 * @param createBucket whether the adapter may create a missing bucket
 */
public record MinioObjectStorageConfig(
        String bucketName,
        long maximumObjectBytes,
        boolean createBucket
) {

    /** Validates S3-compatible bucket naming and the source-size budget. */
    public MinioObjectStorageConfig {
        Objects.requireNonNull(bucketName, "bucketName must not be null");
        bucketName = bucketName.strip().toLowerCase(java.util.Locale.ROOT);
        if (bucketName.length() < 3 || bucketName.length() > 63
                || !bucketName.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]")
                || bucketName.contains("..")) {
            throw new IllegalArgumentException("bucketName is not S3 compatible");
        }
        if (maximumObjectBytes < 1) {
            throw new IllegalArgumentException("maximumObjectBytes must be positive");
        }
    }
}
