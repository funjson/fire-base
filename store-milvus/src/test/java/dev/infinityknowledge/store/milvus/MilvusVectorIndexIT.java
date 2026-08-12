package dev.infinityknowledge.store.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.provider.zhipu.ThreadRetrySleeper;
import dev.infinityknowledge.provider.zhipu.ZhipuEmbeddingConfig;
import dev.infinityknowledge.provider.zhipu.ZhipuEmbeddingProvider;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.vector.VectorIndexRecord;
import dev.infinityknowledge.spi.vector.VectorSearchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract test against a real Milvus 2.6 standalone instance.
 */
@EnabledIfEnvironmentVariable(named = "MILVUS_IT_URI", matches = ".+")
class MilvusVectorIndexIT {

    private static final EmbeddingSpec SPEC = new EmbeddingSpec("test", "model", 4);
    private static final String GENERATION = "it";
    private final String prefix = "it_" + UUID.randomUUID().toString().replace("-", "");
    private final MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
            .uri(System.getenv("MILVUS_IT_URI"))
            .build());

    @AfterEach
    void cleanUp() {
        dropIfPresent(prefix + "_test_model_4_it_metadata_v2");
        dropIfPresent(prefix + "_zhipu_embedding_3_2048_it_metadata_v2");
        client.close();
    }

    private void dropIfPresent(String collectionName) {
        if (client.hasCollection(
                HasCollectionReq.builder().collectionName(collectionName).build()
        )) {
            client.dropCollection(DropCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
        }
    }

    @Test
    void enforcesTenantAndSpaceFiltersInsideMilvus() {
        MilvusVectorIndex index = new MilvusVectorIndex(
                client,
                prefix,
                8,
                allowAllRevisions()
        );
        TenantId tenantA = new TenantId("tenant-a");
        TenantId tenantB = new TenantId("tenant-b");
        KnowledgeSpaceId space = new KnowledgeSpaceId("engineering");
        index.upsert(List.of(
                record(tenantA, space, "alpha", List.of(1.0D, 0.0D, 0.0D, 0.0D)),
                record(tenantB, space, "secret", List.of(1.0D, 0.0D, 0.0D, 0.0D))
        ));

        var result = index.search(new VectorSearchRequest(
                new AccessScope(tenantA, Set.of(space), Set.of()),
                SPEC,
                GENERATION,
                Map.of(),
                List.of(1.0D, 0.0D, 0.0D, 0.0D),
                10
        ));

        assertEquals(1, result.size());
        assertEquals(tenantA, result.getFirst().tenantId());
        assertEquals("alpha", result.getFirst().content());
        assertTrue(result.getFirst().score() > 0.99D);
    }

    @Test
    void enforcesLanguageAndSourceTypeFiltersInsideMilvus() {
        MilvusVectorIndex index = new MilvusVectorIndex(
                client,
                prefix,
                8,
                allowAllRevisions()
        );
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        List<Double> vector = List.of(1.0D, 0.0D, 0.0D, 0.0D);
        index.upsert(List.of(
                record(
                        tenantId,
                        spaceId,
                        "obsidian-zh",
                        "OBSIDIAN",
                        "zh-CN",
                        vector
                ),
                record(
                        tenantId,
                        spaceId,
                        "api-zh",
                        "API",
                        "zh-CN",
                        vector
                ),
                record(
                        tenantId,
                        spaceId,
                        "obsidian-en",
                        "OBSIDIAN",
                        "en-US",
                        vector
                )
        ));

        var result = index.search(new VectorSearchRequest(
                AccessScope.all(tenantId, Set.of(spaceId)),
                SPEC,
                GENERATION,
                Map.of(
                        "language", "zh-CN",
                        "sourceType", "OBSIDIAN"
                ),
                vector,
                10
        ));

        assertEquals(1, result.size());
        assertEquals("obsidian-zh", result.getFirst().content());
        assertEquals("OBSIDIAN", result.getFirst().metadata().get("sourceType"));
        assertEquals("zh-CN", result.getFirst().metadata().get("language"));
    }

    @Test
    void projectsRealGlmEmbeddingAndFindsSemanticMatch() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_ZHIPU_TESTS")));
        String apiKey = System.getenv("ZHIPU_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank());
        EmbeddingSpec productionSpec = new EmbeddingSpec("zhipu", "embedding-3", 2_048);
        MilvusVectorIndex index = new MilvusVectorIndex(
                client,
                prefix,
                8,
                allowAllRevisions()
        );
        ZhipuEmbeddingProvider provider = new ZhipuEmbeddingProvider(
                new ZhipuEmbeddingConfig(
                        URI.create("https://open.bigmodel.cn/api/paas/v4/embeddings"),
                        apiKey,
                        Duration.ofSeconds(30),
                        32,
                        3,
                        Duration.ofMillis(200)
                ),
                embeddingHttpClient(),
                JsonMapper.builder().build(),
                new ThreadRetrySleeper()
        );
        TenantId tenantId = new TenantId("tenant-glm");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("operations");
        String content = "Redis 连接池耗尽时检查连接泄漏、慢命令以及网络抖动。";
        KnowledgeChunk chunk = chunk(tenantId, spaceId, content);
        KnowledgeDocument document = new KnowledgeDocument(
                chunk.documentId(),
                tenantId,
                spaceId,
                "Redis 故障手册",
                new SourceDescriptor(
                        "test",
                        SourceType.API,
                        "redis",
                        "urn:test:redis",
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                95,
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        new DefaultVectorProjectionService(
                provider,
                productionSpec,
                GENERATION,
                index,
                allowAllRevisions()
        ).project(document, List.of(chunk));
        var queryVector = provider.embed(
                List.of("缓存客户端拿不到连接时应该排查什么"),
                productionSpec
        ).getFirst();

        var result = index.search(new VectorSearchRequest(
                new AccessScope(tenantId, Set.of(spaceId), Set.of()),
                productionSpec,
                GENERATION,
                Map.of(),
                queryVector.values(),
                5
        ));

        assertEquals(1, result.size());
        assertEquals(content, result.getFirst().content());
    }

    private static HttpClient embeddingHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        String proxyHost = System.getenv("KNOWLEDGE_EMBEDDING_PROXY_HOST");
        if (proxyHost != null && !proxyHost.isBlank()) {
            String configuredPort = System.getenv("KNOWLEDGE_EMBEDDING_PROXY_PORT");
            int proxyPort = configuredPort == null || configuredPort.isBlank()
                    ? 7_890 : Integer.parseInt(configuredPort.strip());
            builder.proxy(ProxySelector.of(
                    new InetSocketAddress(proxyHost.strip(), proxyPort)
            ));
        }
        return builder.build();
    }

    @Test
    void filtersVectorsBelongingToAnInactiveRevision() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentId documentId = DocumentId.random();
        UUID staleRevision = UUID.randomUUID();
        UUID activeRevision = UUID.randomUUID();
        ActiveRevisionGuard activeOnly = new ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId ignoredTenant,
                    DocumentId ignoredDocument,
                    UUID ignoredRevision
            ) {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId ignoredTenant,
                    List<RetrievalCandidate> candidates
            ) {
                return candidates.stream()
                        .filter(candidate -> activeRevision.equals(candidate.revisionId()))
                        .toList();
            }
        };
        MilvusVectorIndex index = new MilvusVectorIndex(client, prefix, 8, activeOnly);
        index.upsert(List.of(
                record(
                        tenantId,
                        spaceId,
                        documentId,
                        staleRevision,
                        "stale revision",
                        List.of(1.0D, 0.0D, 0.0D, 0.0D)
                ),
                record(
                        tenantId,
                        spaceId,
                        documentId,
                        activeRevision,
                        "active revision",
                        List.of(1.0D, 0.0D, 0.0D, 0.0D)
                )
        ));

        var result = index.search(new VectorSearchRequest(
                new AccessScope(tenantId, Set.of(spaceId), Set.of()),
                SPEC,
                GENERATION,
                Map.of(),
                List.of(1.0D, 0.0D, 0.0D, 0.0D),
                10
        ));

        assertEquals(1, result.size());
        assertEquals(activeRevision, result.getFirst().revisionId());
        assertEquals("active revision", result.getFirst().content());
    }

    @Test
    void overfetchesWhenAnInactiveRevisionOccupiesTheInitialVectorWindow() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentId documentId = DocumentId.random();
        UUID staleRevision = UUID.randomUUID();
        UUID activeRevision = UUID.randomUUID();
        ActiveRevisionGuard activeOnly = new ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId ignoredTenant,
                    DocumentId ignoredDocument,
                    UUID ignoredRevision
            ) {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId ignoredTenant,
                    List<RetrievalCandidate> candidates
            ) {
                return candidates.stream()
                        .filter(candidate -> activeRevision.equals(candidate.revisionId()))
                        .toList();
            }
        };
        MilvusVectorIndex index = new MilvusVectorIndex(client, prefix, 8, activeOnly);
        index.upsert(List.of(
                record(
                        tenantId,
                        spaceId,
                        documentId,
                        staleRevision,
                        "stale exact vector",
                        List.of(1.0D, 0.0D, 0.0D, 0.0D)
                ),
                record(
                        tenantId,
                        spaceId,
                        documentId,
                        activeRevision,
                        "active close vector",
                        List.of(0.9D, 0.1D, 0.0D, 0.0D)
                )
        ));

        var result = index.search(new VectorSearchRequest(
                new AccessScope(tenantId, Set.of(spaceId), Set.of()),
                SPEC,
                GENERATION,
                Map.of(),
                List.of(1.0D, 0.0D, 0.0D, 0.0D),
                1
        ));

        assertEquals(1, result.size());
        assertEquals(activeRevision, result.getFirst().revisionId());
        assertEquals(1, result.getFirst().rank());
    }

    private static VectorIndexRecord record(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String content,
            List<Double> vector
    ) {
        KnowledgeChunk chunk = chunk(tenantId, spaceId, content);
        return new VectorIndexRecord(
                chunk,
                "Document " + content,
                "urn:test:" + content,
                "API",
                "en-US",
                100,
                SPEC,
                GENERATION,
                vector
        );
    }

    private static VectorIndexRecord record(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String content,
            String sourceType,
            String language,
            List<Double> vector
    ) {
        KnowledgeChunk chunk = chunk(tenantId, spaceId, content);
        return new VectorIndexRecord(
                chunk,
                "Document " + content,
                "urn:test:" + content,
                sourceType,
                language,
                100,
                SPEC,
                GENERATION,
                vector
        );
    }

    private static VectorIndexRecord record(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            String content,
            List<Double> vector
    ) {
        return new VectorIndexRecord(
                chunk(tenantId, spaceId, documentId, revisionId, content),
                "Document " + content,
                "urn:test:" + content.replace(' ', '-'),
                "API",
                "en-US",
                100,
                SPEC,
                GENERATION,
                vector
        );
    }

    private static KnowledgeChunk chunk(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String content
    ) {
        UUID documentId = UUID.nameUUIDFromBytes(
                (tenantId.value() + content).getBytes(StandardCharsets.UTF_8)
        );
        UUID revisionId = UUID.nameUUIDFromBytes(
                (documentId + ":1").getBytes(StandardCharsets.UTF_8)
        );
        return chunk(
                tenantId,
                spaceId,
                new DocumentId(documentId),
                revisionId,
                content
        );
    }

    private static KnowledgeChunk chunk(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            String content
    ) {
        UUID chunkId = UUID.nameUUIDFromBytes(
                (revisionId + ":0").getBytes(StandardCharsets.UTF_8)
        );
        return new KnowledgeChunk(
                chunkId,
                tenantId,
                spaceId,
                documentId,
                revisionId,
                List.of(UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8))),
                0,
                List.of("Test"),
                content,
                "hash-" + content,
                Map.of()
        );
    }

    private static ActiveRevisionGuard allowAllRevisions() {
        return new ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId tenantId,
                    DocumentId documentId,
                    UUID revisionId
            ) {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retainActive(
                    TenantId tenantId,
                    List<RetrievalCandidate> candidates
            ) {
                return List.copyOf(candidates);
            }
        };
    }
}
