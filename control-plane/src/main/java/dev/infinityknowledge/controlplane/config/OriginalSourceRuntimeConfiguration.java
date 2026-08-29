package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.spi.objectstorage.ObjectStorage;
import dev.infinityknowledge.spi.objectstorage.SourceObjectCatalog;
import dev.infinityknowledge.store.minio.MinioObjectStorage;
import dev.infinityknowledge.store.minio.MinioObjectStorageConfig;
import dev.infinityknowledge.store.postgres.PostgresSourceObjectCatalog;
import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 组装 MinIO 原件存储与 PostgreSQL 原件目录。
 */
@Configuration
public class OriginalSourceRuntimeConfiguration {

    /**
     * 创建官方 MinIO 客户端；构造阶段不发起网络访问。
     */
    @Bean
    MinioClient sourceMinioClient(ObjectStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint().toString())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    /**
     * 注册按租户与空间隔离的原件对象存储。
     */
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

    /**
     * 注册活动修订原件目录查询适配器。
     */
    @Bean
    SourceObjectCatalog sourceObjectCatalog(JdbcTemplate jdbc) {
        return new PostgresSourceObjectCatalog(jdbc);
    }
}
