package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ActiveIndexGeneration;
import dev.infinityknowledge.spi.indexing.IndexGenerationIdentity;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证检索只能读取与当前可执行合同一致的活动索引代际。 */
class ContractValidatingActiveIndexGenerationCatalogTest {

    private static final TenantId TENANT = new TenantId("tenant-index-contract");
    private static final KnowledgeSpaceId SPACE = new KnowledgeSpaceId("engineering");
    private static final EmbeddingSpec SPEC = new EmbeddingSpec(
            "zhipu",
            "embedding-3",
            2_048
    );
    private static final IndexingContract CONTRACT = new IndexingContract(
            "normalizer-v1",
            "chunker-v1"
    );

    @Test
    void returnsGenerationWhenExecutionContractMatches() {
        IndexPhysicalContract physicalContract = IndexPhysicalContract.baseline("v2");
        ActiveIndexGeneration expected = generation(configurationVersion(physicalContract));
        var catalog = new ContractValidatingActiveIndexGenerationCatalog(
                (tenantId, spaceId) -> Optional.of(expected),
                SPEC,
                physicalContract,
                (tenantId, spaceId) -> CONTRACT
        );

        assertEquals(Optional.of(expected), catalog.findActiveGeneration(TENANT, SPACE));
    }

    @Test
    void rejectsGenerationBeforeRetrievalWhenPhysicalTargetDrifts() {
        ActiveIndexGeneration active = generation(configurationVersion(
                IndexPhysicalContract.withKeywordTarget("v2", "a".repeat(64))
        ));
        var catalog = new ContractValidatingActiveIndexGenerationCatalog(
                (tenantId, spaceId) -> Optional.of(active),
                SPEC,
                IndexPhysicalContract.withKeywordTarget("v2", "b".repeat(64)),
                (tenantId, spaceId) -> CONTRACT
        );

        assertThrows(
                IllegalStateException.class,
                () -> catalog.findActiveGeneration(TENANT, SPACE)
        );
    }

    private static ActiveIndexGeneration generation(String configurationVersion) {
        return new ActiveIndexGeneration(
                SPACE,
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                configurationVersion
        );
    }

    private static String configurationVersion(IndexPhysicalContract physicalContract) {
        return IndexGenerationIdentity.configurationVersion(
                SPEC,
                physicalContract,
                CONTRACT.normalizerVersion(),
                CONTRACT.chunkerVersion()
        );
    }
}
