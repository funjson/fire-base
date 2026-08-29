package dev.infinityknowledge.domain.retrieval;

import dev.infinityknowledge.domain.common.DomainChecks;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示单个 Retriever 返回且尚未融合的候选块。
 *
 * @param chunkId Chunk 标识
 * @param tenantId 租户标识
 * @param spaceId 知识空间
 * @param documentId 文档标识
 * @param revisionId 修订标识
 * @param channel 召回通道
 * @param rank 通道内一基排名
 * @param score 通道归一化评分
 * @param title 文档标题
 * @param sectionPath 章节路径
 * @param content 候选正文
 * @param sourceUri 来源 URI
 * @param metadata 元数据
 * @param sourceSpans 可选的原始文本定位范围
 */
public record RetrievalCandidate(
        UUID chunkId,
        TenantId tenantId,
        KnowledgeSpaceId spaceId,
        DocumentId documentId,
        UUID revisionId,
        RetrievalChannel channel,
        int rank,
        double score,
        String title,
        List<String> sectionPath,
        String content,
        String sourceUri,
        Map<String, String> metadata,
        List<ChunkSourceSpan> sourceSpans
) {

    /**
     * 校验候选租户归属、排名、评分及可引用来源。
     */
    public RetrievalCandidate {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        score = DomainChecks.unitScore(score, "candidate score");
        title = DomainChecks.requiredText(title, "title", 512);
        sectionPath = List.copyOf(
                Objects.requireNonNull(sectionPath, "sectionPath must not be null")
        );
        content = DomainChecks.requiredText(content, "candidate content", 100_000);
        sourceUri = DomainChecks.requiredText(sourceUri, "sourceUri", 2048);
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        sourceSpans = List.copyOf(Objects.requireNonNull(
                sourceSpans,
                "sourceSpans must not be null"
        ));
    }

    /** 兼容尚未传回高亮范围的检索适配器。 */
    public RetrievalCandidate(
            UUID chunkId, TenantId tenantId, KnowledgeSpaceId spaceId, DocumentId documentId,
            UUID revisionId, RetrievalChannel channel, int rank, double score, String title,
            List<String> sectionPath, String content, String sourceUri, Map<String, String> metadata
    ) {
        this(
                chunkId, tenantId, spaceId, documentId, revisionId, channel, rank, score, title,
                sectionPath, content, sourceUri, metadata, List.of()
        );
    }
}
