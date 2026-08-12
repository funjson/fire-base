package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Objects;

/** MinIO connection settings for retained original documents. */
@ConfigurationProperties(prefix = "infinity.knowledge.object-storage.minio")
public record ObjectStorageProperties(
        URI endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        long maximumObjectBytes,
        boolean createBucket
) {

    /** Validates endpoint and required credentials without logging them. */
    public ObjectStorageProperties {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("object storage endpoint must use http or https");
        }
        accessKey = required(accessKey, "accessKey");
        secretKey = required(secretKey, "secretKey");
        bucket = required(bucket, "bucket");
        if (maximumObjectBytes < 1) {
            throw new IllegalArgumentException("maximumObjectBytes must be positive");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
