package dev.infinityknowledge.domain;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.evidence.Citation;
import dev.infinityknowledge.domain.evidence.Evidence;
import dev.infinityknowledge.domain.identity.PrincipalContext;
import dev.infinityknowledge.domain.identity.PrincipalId;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.domain.retrieval.KnowledgeQuery;
import dev.infinityknowledge.domain.retrieval.RetrievalChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证领域对象在进入 Runtime 前拒绝不安全或不可解释的数据。
 */
class DomainInvariantTest {

    /**
     * 验证查询预算不能无限放大。
     */
    @Test
    void rejectsUnboundedTopK() {
        PrincipalContext principal = principal("tenant-a");
        assertThrows(IllegalArgumentException.class, () -> KnowledgeQuery.online(
                UUID.randomUUID(),
                principal,
                "如何处理订单超时",
                Set.of(),
                101,
                Map.of()
        ));
    }

    /**
     * Agent 直连 Runtime 时也不能通过未知过滤键静默放宽查询。
     */
    @Test
    void rejectsUnknownRetrievalFilters() {
        PrincipalContext principal = principal("tenant-a");

        assertThrows(IllegalArgumentException.class, () -> KnowledgeQuery.online(
                UUID.randomUUID(),
                principal,
                "如何处理订单超时",
                Set.of(),
                8,
                Map.of("department", "engineering")
        ));
    }

    /**
     * 非 HTTP Agent 调用也应把空过滤键作为领域错误，而不是泄漏 NPE。
     */
    @Test
    void rejectsNullRetrievalFilterKeys() {
        PrincipalContext principal = principal("tenant-a");
        Map<String, String> filters = new HashMap<>();
        filters.put(null, "zh-CN");

        assertThrows(IllegalArgumentException.class, () -> KnowledgeQuery.online(
                UUID.randomUUID(),
                principal,
                "如何处理订单超时",
                Set.of(),
                8,
                filters
        ));
    }

    /**
     * 验证非法相关性评分不会进入证据包。
     */
    @Test
    void rejectsNonFiniteEvidenceScore() {
        Citation citation = new Citation(
                DocumentId.random(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "订单故障手册",
                List.of("超时处理"),
                "https://knowledge.example/doc"
        );
        assertThrows(IllegalArgumentException.class, () -> new Evidence(
                UUID.randomUUID(),
                "检查连接池配置。",
                Double.NaN,
                80,
                Set.of(RetrievalChannel.KEYWORD),
                citation
        ));
    }

    /**
     * 验证租户标识会被规范化但不会改变业务值。
     */
    @Test
    void normalizesTenantId() {
        assertEquals("tenant-a", new TenantId(" tenant-a ").value());
    }

    /**
     * Chunk 正文不能在领域构造时再次裁剪，否则内容哈希和 SourceSpan 会指向另一份文本。
     */
    @Test
    void preservesChunkTextExactly() {
        UUID elementId = UUID.randomUUID();
        String content = " 前导与尾随空白。\n";
        String contextualText = "章节\n\n" + content;

        KnowledgeChunk chunk = new KnowledgeChunk(
                UUID.randomUUID(),
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                DocumentId.random(),
                UUID.randomUUID(),
                List.of(elementId),
                List.of(new ChunkSourceSpan(elementId, 0, content.length(), null)),
                0,
                List.of("章节"),
                content,
                contextualText,
                "content-hash",
                Map.of()
        );

        assertEquals(content, chunk.content());
        assertEquals(contextualText, chunk.contextualText());
    }

    /**
     * Chunk 必须保留可用于引用和高亮的原文范围。
     */
    @Test
    void rejectsMissingChunkSourceSpans() {
        UUID elementId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> chunk(
                List.of(elementId),
                List.of()
        ));
    }

    /**
     * Chunk 声明的来源元素必须与实际引用的元素完全一致。
     */
    @Test
    void rejectsChunkSourceSpansThatDoNotMatchElements() {
        UUID declaredElement = UUID.randomUUID();
        UUID undeclaredElement = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> chunk(
                List.of(declaredElement),
                List.of(new ChunkSourceSpan(undeclaredElement, 0, 1, null))
        ));
        assertThrows(IllegalArgumentException.class, () -> chunk(
                List.of(declaredElement, undeclaredElement),
                List.of(new ChunkSourceSpan(declaredElement, 0, 1, null))
        ));
    }

    /**
     * 元素标识是 Chunk 来源集合，重复值只会制造虚假的来源数量。
     */
    @Test
    void rejectsDuplicateChunkElementIds() {
        UUID elementId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> chunk(
                List.of(elementId, elementId),
                List.of(new ChunkSourceSpan(elementId, 0, 1, null))
        ));
    }

    /**
     * 限制单个 Chunk 的来源范围数量，避免无界引用元数据进入投影。
     */
    @Test
    void rejectsTooManyChunkSourceSpans() {
        UUID elementId = UUID.randomUUID();
        List<ChunkSourceSpan> spans = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> new ChunkSourceSpan(elementId, index, index + 1, null))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> chunk(
                List.of(elementId),
                spans
        ));
    }

    private KnowledgeChunk chunk(List<UUID> elementIds, List<ChunkSourceSpan> sourceSpans) {
        return new KnowledgeChunk(
                UUID.randomUUID(),
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                DocumentId.random(),
                UUID.randomUUID(),
                elementIds,
                sourceSpans,
                0,
                List.of(),
                "正文",
                "正文",
                "content-hash",
                Map.of()
        );
    }

    /**
     * 创建测试主体。
     *
     * @param tenant 租户值
     * @return 主体上下文
     */
    private PrincipalContext principal(String tenant) {
        return new PrincipalContext(
                new TenantId(tenant),
                new PrincipalId("user-1"),
                Set.of("reader"),
                Set.of("engineering"),
                false
        );
    }
}
