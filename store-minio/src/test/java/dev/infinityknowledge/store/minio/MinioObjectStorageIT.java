package dev.infinityknowledge.store.minio;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 Compose 中的真实 MinIO 验证原件写入、完整性元数据、下载和精确清理。
 *
 * <p>测试只删除本次生成的随机对象，不清空 Bucket，也不接触其他租户对象。</p>
 */
@EnabledIfEnvironmentVariable(named = "RUN_MINIO_TESTS", matches = "true")
class MinioObjectStorageIT {

    @Test
    void roundTripsSourceAssetAgainstRealMinio() throws Exception {
        String endpoint = environment("KNOWLEDGE_MINIO_ENDPOINT", "http://localhost:9002");
        String accessKey = environment("KNOWLEDGE_MINIO_ACCESS_KEY", "minioadmin");
        String secretKey = environment("KNOWLEDGE_MINIO_SECRET_KEY", "minioadmin");
        String bucket = environment(
                "KNOWLEDGE_MINIO_BUCKET",
                "infinity-knowledge-source"
        );
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        MinioObjectStorage storage = new MinioObjectStorage(
                client,
                new MinioObjectStorageConfig(bucket, 25L * 1024L * 1024L, true)
        );
        String objectId = "acceptance-" + UUID.randomUUID();
        ObjectAddress address = new ObjectAddress(
                new TenantId("acceptance-tenant"),
                new KnowledgeSpaceId("extraction"),
                objectId
        );
        byte[] source = "真实 MinIO 抽取原件\nsecond line".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(source);
        ObjectWriteRequest request = new ObjectWriteRequest(
                address,
                "抽取验收.txt",
                "text/plain",
                source.length,
                checksum,
                Map.of("suite", "extraction-acceptance")
        );

        try {
            var written = storage.put(request, new ByteArrayInputStream(source));
            assertThat(written.checksumSha256()).isEqualTo(checksum);
            assertThat(written.contentLength()).isEqualTo(source.length);

            var head = storage.head(address).orElseThrow();
            assertThat(head.originalFileName()).isEqualTo("抽取验收.txt");
            assertThat(head.mediaType()).isEqualTo("text/plain");
            assertThat(head.attributes()).containsEntry("suite", "extraction-acceptance");

            try (var stored = storage.get(address).orElseThrow().content()) {
                assertThat(stored.readAllBytes()).isEqualTo(source);
            }
        } finally {
            storage.delete(address);
        }

        assertThat(storage.head(address)).isEmpty();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String sha256(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}
