package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用平台确定性结构规划的公开 Chunker。
 *
 * <p>该类主要用于无 Provider 注册表的嵌入式调用和聚焦测试；生产 Factory 仍创建
 * 同一个 ManagedKnowledgeChunker 执行链路。</p>
 */
public final class StructuralKnowledgeChunker implements KnowledgeChunker {
    /** 结构规则语义变化时必须更新。 */
    public static final String VERSION = "structural-chunker-v2";

    private final ManagedKnowledgeChunker delegate;

    /** 创建绑定明确 Token 计数与大小约束的结构 Chunker。 */
    public StructuralKnowledgeChunker(ChunkSizing sizing) {
        delegate = new ManagedKnowledgeChunker(
                KnowledgeChunkerFactory.STRUCTURAL,
                VERSION,
                new StructuralBoundaryStrategy(),
                Objects.requireNonNull(sizing, "sizing must not be null")
        );
    }

    @Override
    public String contract() {
        return delegate.contract();
    }

    @Override
    public ChunkingResult chunk(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            DocumentId documentId,
            UUID revisionId,
            List<KnowledgeElement> elements
    ) {
        return delegate.chunk(tenantId, spaceId, documentId, revisionId, elements);
    }
}
