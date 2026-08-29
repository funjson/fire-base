package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

/**
 * 按知识空间解析当前索引代际使用的处理契约。
 *
 * <p>向量与关键词投影必须通过同一入口读取空间文档处理配置，避免其中一个通道仍使用
 * 部署默认 Parser 或 Chunker，导致同一空间被写入不同处理语义。</p>
 */
@FunctionalInterface
public interface SpaceIndexingContractResolver {

    /** 返回目标空间当前生效文档处理配置对应的不可变索引契约。 */
    IndexingContract resolve(TenantId tenantId, KnowledgeSpaceId spaceId);
}
