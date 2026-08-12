package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.SourceObjectCatalog;
import dev.infinityknowledge.store.minio.MinioObjectStorage;
import dev.infinityknowledge.store.minio.MinioObjectStorageConfig;
import dev.infinityknowledge.store.postgres.PostgresSourceObjectCatalog;
import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires rich-document parsers, MinIO and the transactional source-object catalog. */
@Configuration
public class OriginalSourceRuntimeConfiguration {

    /** Registers the deterministic built-in parser registry. */
    @Bean
    DocumentParserRegistry documentParserRegistry() {
        return DocumentParserRegistry.standard();
    }

    /** Creates the official MinIO client without performing network I/O at startup. */
    @Bean
    MinioClient sourceMinioClient(ObjectStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint().toString())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    /** Registers tenant-isolated original object storage. */
    @Bean
    ObjectStorage objectStorage(
            MinioClient sourceMinioClient,
            ObjectStorageProperties properties
    ) {
        return new MinioObjectStorage(
                sourceMinioClient,
                new MinioObjectStorageConfig(
                        properties.bucket(),
                        properties.maximumObjectBytes(),
                        properties.createBucket()
                )
        );
    }

    /** Registers active-revision source-object lookup. */
    @Bean
    SourceObjectCatalog sourceObjectCatalog(JdbcTemplate jdbc) {
        return new PostgresSourceObjectCatalog(jdbc);
    }
}
