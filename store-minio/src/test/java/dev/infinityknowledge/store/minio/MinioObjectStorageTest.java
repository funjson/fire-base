package dev.infinityknowledge.store.minio;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.objectstorage.ObjectAddress;
import dev.infinityknowledge.spi.objectstorage.ObjectWriteRequest;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinioObjectStorageTest {

    @Test
    void physicalKeyEncodesTenantAndSpaceSegments() {
        ObjectAddress address = new ObjectAddress(
                new TenantId("../tenant-a"),
                new KnowledgeSpaceId("space/../../b"),
                "source-1"
        );

        String key = MinioObjectKey.from(address);

        assertThat(key).startsWith("tenants/").endsWith("/objects/source-1");
        assertThat(key).doesNotContain("..", "tenant-a", "space/../../b");
    }

    @Test
    void rejectsOversizedUploadBeforeNetworkAccess() {
        MinioObjectStorage storage = new MinioObjectStorage(
                MinioClient.builder().endpoint("http://127.0.0.1:9000").build(),
                new MinioObjectStorageConfig("knowledge-source", 4, false)
        );
        ObjectWriteRequest request = new ObjectWriteRequest(
                new ObjectAddress(
                        new TenantId("tenant-a"),
                        new KnowledgeSpaceId("ops"),
                        "source-1"
                ),
                "source.txt",
                "text/plain",
                5,
                "a".repeat(64),
                Map.of()
        );

        assertThatThrownBy(() -> storage.put(request, new ByteArrayInputStream(new byte[5])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumObjectBytes");
    }
}
