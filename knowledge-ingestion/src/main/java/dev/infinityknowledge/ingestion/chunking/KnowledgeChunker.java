package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.UUID;

/**
 * 将有序结构元素转换为稳定检索单元的最小扩展契约。
 */
public interface KnowledgeChunker {

    /**
     * 返回包含算法版本和实际参数的不可变处理契约。
     *
     * @return 可进入修订处理指纹的切分契约
     */
    String contract();

    /**
     * 按来源顺序切分一个不可变文档修订。
     *
     * @param tenantId 租户标识
     * @param spaceId 知识空间标识
     * @param documentId 文档标识
     * @param revisionId 修订标识
     * @param elements 按 ordinal 严格递增的结构元素
     * @return 确定性排序的检索单元
     */
    ChunkingResult chunk(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            List<KnowledgeElement> elements
    );
}
