package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.config.SpaceDocumentProcessingConfigResolver;

import java.util.Objects;

/**
 * 由空间文档处理配置生成 Parser、清洗和 Chunker 的索引代际契约。
 *
 * <p>本组件只读取配置并计算稳定指纹，不解析文档、不执行 Chunk，也不会调用
 * Embedding 模型。模型身份和预算只作为语义 Chunker 契约的一部分参与指纹。</p>
 */
public final class DocumentProcessingConfigIndexingContractResolver
        implements SpaceIndexingContractResolver {

    private final SpaceDocumentProcessingConfigResolver configResolver;

    /** 创建使用摄取主线同一文档处理配置解析器的索引契约解析器。 */
    public DocumentProcessingConfigIndexingContractResolver(
            SpaceDocumentProcessingConfigResolver configResolver
    ) {
        this.configResolver = Objects.requireNonNull(
                configResolver,
                "configResolver must not be null"
        );
    }

    @Override
    public IndexingContract resolve(TenantId tenantId, KnowledgeSpaceId spaceId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        var config = configResolver.resolveStored(tenantId, spaceId);
        if (!tenantId.equals(config.tenantId()) || !spaceId.equals(config.spaceId())) {
            throw new IllegalStateException(
                    "resolved document processing config does not belong to requested space"
            );
        }
        return IndexingContract.fromProcessingContract(
                Objects.requireNonNull(
                        config.processingContract(),
                        "persisted processing contract must not be null"
                )
        );
    }
}
