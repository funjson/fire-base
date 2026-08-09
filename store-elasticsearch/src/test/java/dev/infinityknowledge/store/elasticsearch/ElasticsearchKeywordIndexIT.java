package dev.infinityknowledge.store.elasticsearch;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.DocumentStatus;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeDocument;
import dev.infinityknowledge.domain.document.SourceDescriptor;
import dev.infinityknowledge.domain.document.SourceType;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.access.AccessScope;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.spi.indexing.ProjectionSource;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.spi.retrieval.QueryPlan;
import dev.infinityknowledge.spi.retrieval.RetrievalRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests against a real Elasticsearch server.
 */
@EnabledIfEnvironmentVariable(named = "ELASTICSEARCH_IT_URI", matches = ".+")
class ElasticsearchKeywordIndexIT {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final URI endpoint = URI.create(System.getenv("ELASTICSEARCH_IT_URI"));
    private final String indexName = "it_"
            + UUID.randomUUID().toString().replace("-", "");
    private final ElasticsearchKeywordIndex index = new ElasticsearchKeywordIndex(
            httpClient,
            JsonMapper.builder().build(),
            new ElasticsearchConfig(endpoint, indexName, "", Duration.ofSeconds(15)),
            allowAllRevisions()
    );

    @AfterEach
    void cleanUp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        endpoint.resolve("/" + indexName)
                )
                .DELETE()
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void filtersTenantAndDocumentScopeInsideElasticsearch() {
        TenantId tenantA = new TenantId("tenant-a");
        TenantId tenantB = new TenantId("tenant-b");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        ProjectionSource allowed = source(
                tenantA,
                spaceId,
                "allowed",
                "ERR-50231 Redis 连接池耗尽处理手册"
        );
        ProjectionSource forbidden = source(
                tenantB,
                spaceId,
                "forbidden",
                "ERR-50231 仅租户 B 可见的敏感处理步骤"
        );
        index.upsert(allowed);
        index.upsert(forbidden);

        var result = index.retrieve(request(
                tenantA,
                spaceId,
                allowed.document().id(),
                "ERR-50231"
        ));

        assertEquals(1, result.size());
        assertEquals(tenantA, result.getFirst().tenantId());
        assertEquals(allowed.document().id(), result.getFirst().documentId());
        assertEquals(RetrievalChannel.KEYWORD, result.getFirst().channel());
        assertTrue(result.getFirst().content().contains("Redis"));
    }

    @Test
    void reverseCompletionCannotDeleteTheNewActiveRevision() {
        TenantId tenantId = new TenantId("tenant-a");
        KnowledgeSpaceId spaceId = new KnowledgeSpaceId("engineering");
        DocumentId documentId = DocumentId.random();
        UUID oldRevision = UUID.randomUUID();
        UUID activeRevision = UUID.randomUUID();
        ActiveRevisionGuard activeOnly = new ActiveRevisionGuard() {
            @Override
            public boolean isActive(
                    TenantId ignoredTenant,
                    DocumentId ignoredDocument,
                    UUID ignoredRevision
            ) {
                // Both workers may have passed their publication check before completion order flips.
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
        ElasticsearchKeywordIndex guardedIndex = new ElasticsearchKeywordIndex(
                httpClient,
                JsonMapper.builder().build(),
                new ElasticsearchConfig(endpoint, indexName, "", Duration.ofSeconds(15)),
                activeOnly
        );
        ProjectionSource current = source(
                tenantId,
                spaceId,
                documentId,
                activeRevision,
                "shared",
                "ACTIVE-REVISION-7319 Redis connection guidance"
        );
        ProjectionSource stale = source(
                tenantId,
                spaceId,
                documentId,
                oldRevision,
                "shared",
                "STALE-REVISION-7319 obsolete guidance"
        );

        guardedIndex.upsert(current);
        guardedIndex.upsert(stale);

        var result = guardedIndex.retrieve(request(
                tenantId,
                spaceId,
                documentId,
                "REVISION-7319"
        ));
        assertEquals(1, result.size());
        assertEquals(activeRevision, result.getFirst().revisionId());
        assertTrue(result.getFirst().content().contains("ACTIVE-REVISION"));
    }

    private static RetrievalRequest request(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            String text
    ) {
        PrincipalContext principal = new PrincipalContext(
                tenantId,
                new PrincipalId("user-1"),
                Set.of("knowledge-reader"),
                Set.of("engineering"),
                false
        );
        KnowledgeQuery query = new KnowledgeQuery(
                UUID.randomUUID(),
                principal,
                text,
                Set.of(spaceId),
                5,
                Map.of()
        );
        return new RetrievalRequest(
                query,
                new QueryPlan(
                        text,
                        text,
                        Set.of(RetrievalChannel.KEYWORD),
                        10
                ),
                new AccessScope(
                        tenantId,
                        Set.of(spaceId),
                        Set.of(documentId.value().toString())
                )
        );
    }

    private static ProjectionSource source(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            String externalId,
            String content
    ) {
        UUID documentUuid = UUID.nameUUIDFromBytes(
                (tenantId.value() + ":" + externalId).getBytes(StandardCharsets.UTF_8)
        );
        DocumentId documentId = new DocumentId(documentUuid);
        UUID revisionId = UUID.nameUUIDFromBytes(
                (documentUuid + ":1").getBytes(StandardCharsets.UTF_8)
        );
        return source(
                tenantId,
                spaceId,
                documentId,
                revisionId,
                externalId,
                content
        );
    }

    private static ProjectionSource source(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            String externalId,
            String content
    ) {
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                tenantId,
                spaceId,
                "故障处理 " + externalId,
                new SourceDescriptor(
                        "it",
                        SourceType.API,
                        externalId,
                        "urn:it:" + externalId,
                        Map.of()
                ),
                DocumentStatus.ACTIVE,
                90,
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        KnowledgeChunk chunk = new KnowledgeChunk(
                UUID.nameUUIDFromBytes(
                        (revisionId + ":0").getBytes(StandardCharsets.UTF_8)
                ),
                tenantId,
                spaceId,
                documentId,
                revisionId,
                List.of(UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8))),
                0,
                List.of("故障排查"),
                content,
                "hash-" + externalId,
                Map.of("language", "zh-CN")
        );
        return new ProjectionSource(document, List.of(chunk));
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
