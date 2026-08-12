package dev.infinityknowledge.store.milvus;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.vector.VectorSearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MilvusVectorIndexFilterTest {

    @Test
    void combinesAclAndMetadataPredicatesWithoutDroppingEither() {
        var request = new VectorSearchRequest(
                AccessScope.only(
                        new TenantId("tenant-a"),
                        Set.of(new KnowledgeSpaceId("engineering")),
                        Set.of("document-a")
                ),
                new EmbeddingSpec("test", "model", 2),
                "v2",
                Map.of(
                        "language", "zh-CN",
                        "sourceType", "OBSIDIAN"
                ),
                List.of(1.0D, 0.0D),
                10
        );

        assertEquals(
                "tenant_id == {tenantId} && space_id in {spaceIds}"
                        + " && document_id in {documentIds}"
                        + " && source_type == {sourceType}"
                        + " && language == {language}",
                MilvusVectorIndex.filter(request)
        );
        assertEquals(
                Map.of(
                        "tenantId", "tenant-a",
                        "spaceIds", List.of("engineering"),
                        "documentIds", List.of("document-a"),
                        "sourceType", "OBSIDIAN",
                        "language", "zh-CN"
                ),
                MilvusVectorIndex.filterValues(request)
        );
    }
}
