package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.indexing.ActiveIndexGeneration;
import dev.infinityknowledge.spi.indexing.ActiveIndexGenerationCatalog;
import dev.infinityknowledge.spi.indexing.IndexGenerationIdentity;
import dev.infinityknowledge.spi.indexing.IndexPhysicalContract;

import java.util.Objects;
import java.util.Optional;

/**
 * 在检索读取活动代际时校验它仍与当前可执行索引合同一致。
 *
 * <p>Milvus Collection 与 Elasticsearch 索引都由部署配置绑定；任一物理目标或映射变化后
 * 必须在召回前拒绝，而不能查询新目标却把数据库中的旧代际写入观测。后续若支持多模型、
 * 多代际动态路由，可用持久化物理目标的 Catalog 实现替换本装饰器。</p>
 */
public final class ContractValidatingActiveIndexGenerationCatalog
        implements ActiveIndexGenerationCatalog {

    private final ActiveIndexGenerationCatalog delegate;
    private final EmbeddingSpec embeddingSpec;
    private final IndexPhysicalContract physicalContract;
    private final SpaceIndexingContractResolver indexingContractResolver;

    /** 创建 fail-closed 的活动代际目录。 */
    public ContractValidatingActiveIndexGenerationCatalog(
            ActiveIndexGenerationCatalog delegate,
            EmbeddingSpec embeddingSpec,
            IndexPhysicalContract physicalContract,
            SpaceIndexingContractResolver indexingContractResolver
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.embeddingSpec = Objects.requireNonNull(
                embeddingSpec,
                "embeddingSpec must not be null"
        );
        this.physicalContract = Objects.requireNonNull(
                physicalContract,
                "physicalContract must not be null"
        );
        this.indexingContractResolver = Objects.requireNonNull(
                indexingContractResolver,
                "indexingContractResolver must not be null"
        );
    }

    @Override
    public Optional<ActiveIndexGeneration> findActiveGeneration(
            TenantId tenantId,
            KnowledgeSpaceId spaceId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Optional<ActiveIndexGeneration> active = delegate.findActiveGeneration(
                tenantId,
                spaceId
        );
        if (active.isEmpty()) {
            return active;
        }
        IndexingContract contract = indexingContractResolver.resolve(tenantId, spaceId);
        String expected = IndexGenerationIdentity.configurationVersion(
                embeddingSpec,
                physicalContract,
                contract.normalizerVersion(),
                contract.chunkerVersion()
        );
        if (!expected.equals(active.orElseThrow().configurationVersion())) {
            throw new IllegalStateException(
                    "active index generation differs from deployed retrieval contract"
            );
        }
        return active;
    }

}
