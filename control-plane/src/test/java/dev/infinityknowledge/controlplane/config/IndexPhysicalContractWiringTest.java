package dev.infinityknowledge.controlplane.config;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.retrieval.RetrievalCandidate;
import dev.infinityknowledge.spi.indexing.ActiveRevisionGuard;
import dev.infinityknowledge.store.elasticsearch.ElasticsearchConfig;
import dev.infinityknowledge.store.elasticsearch.ElasticsearchKeywordIndex;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证启用与关闭 Elasticsearch 时运行时只提供一份统一物理索引合同。 */
class IndexPhysicalContractWiringTest {

    @Test
    void keepsBaselineGenerationWhenElasticsearchIsDisabled() {
        var contract = new KnowledgeRuntimeConfiguration()
                .baselineIndexPhysicalContract(embedding());

        assertThat(contract.vectorGeneration()).isEqualTo("v2");
        assertThat(contract.hasKeywordTarget()).isFalse();
    }

    @Test
    void bindsTheActualElasticsearchTargetWhenEnabled() {
        ElasticsearchKeywordIndex keywordIndex = keywordIndex("knowledge_chunks_v2");
        var contract = new ElasticsearchRuntimeConfiguration()
                .elasticsearchIndexPhysicalContract(embedding(), keywordIndex);

        assertThat(contract.vectorGeneration()).isEqualTo("v2");
        assertThat(contract.keywordTargetFingerprint())
                .isEqualTo(keywordIndex.physicalTargetFingerprint());
    }

    private static EmbeddingProperties embedding() {
        return new EmbeddingProperties(
                false,
                "zhipu",
                "embedding-3",
                2_048,
                "v2",
                URI.create("https://example.invalid/embeddings"),
                "",
                Duration.ofSeconds(30),
                32,
                3,
                Duration.ofMillis(250),
                "",
                0
        );
    }

    private static ElasticsearchKeywordIndex keywordIndex(String indexName) {
        return new ElasticsearchKeywordIndex(
                HttpClient.newHttpClient(),
                JsonMapper.builder().build(),
                new ElasticsearchConfig(
                        URI.create("http://localhost:9200"),
                        indexName,
                        "",
                        Duration.ofSeconds(5)
                ),
                new ActiveRevisionGuard() {
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
                }
        );
    }
}
