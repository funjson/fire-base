package dev.infinityknowledge.spi.indexing;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;

import java.time.Instant;
import java.util.UUID;

/**
 * 持久化不可变索引代际与逐文档投影状态。
 */
public interface IndexProjectionStore {

    /**
     * 返回空间当前活动代际；空间尚无代际时创建它。
     *
     * <p>活动代际的物理目标或处理契约不匹配时必须拒绝，不能把不同 Elasticsearch
     * 索引、Parser、规范化或 Chunk 语义的结果静默混入同一代际。</p>
     */
    UUID resolveActiveGeneration(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            EmbeddingSpec embeddingSpec,
            IndexPhysicalContract physicalContract,
            String normalizerVersion,
            String chunkerVersion,
            Instant now
    );

    /**
     * 记录一个投影通道的状态，同时保留其余通道的已有状态。
     */
    default void recordProjectionStatus(
            TenantId tenantId,
            UUID generationId,
            DocumentId documentId,
            UUID revisionId,
            ProjectionType projectionType,
            ProjectionStatus status,
            Instant now
    ) {
        if (projectionType != ProjectionType.VECTOR) {
            throw new UnsupportedOperationException(
                    "projection store does not support " + projectionType
            );
        }
        recordVectorStatus(
                tenantId,
                generationId,
                documentId,
                revisionId,
                status,
                now
        );
    }

    /**
     * 为旧向量投影器保留的兼容入口。
     */
    void recordVectorStatus(
            TenantId tenantId,
            UUID generationId,
            DocumentId documentId,
            UUID revisionId,
            ProjectionStatus status,
            Instant now
    );
}
