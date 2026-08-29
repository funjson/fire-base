package dev.infinityknowledge.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.chunking.ChunkSizing;
import dev.infinityknowledge.ingestion.chunking.StructuralKnowledgeChunker;
import dev.infinityknowledge.ingestion.chunking.Utf8ByteBudgetTokenCounter;
import dev.infinityknowledge.ingestion.parser.DocumentParseInput;
import dev.infinityknowledge.ingestion.parser.DocumentParseLimits;
import dev.infinityknowledge.ingestion.parser.MarkdownDocumentParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownIngestionTest {

    @Test
    void preservesStructureAndExposesFrontMatterForCleanerGovernance() {
        UUID revisionId = UUID.randomUUID();
        String markdown = """
                ---
                title: 登录排障
                confidential: true
                ---
                # 用户中心

                普通说明段落。

                ## 诊断

                - 检查网关
                - 检查 Redis

                | 项目 | 值 |
                | --- | --- |
                | 超时 | 3s |

                ```java
                callGateway();
                ```
                """;

        List<KnowledgeElement> elements = parse(revisionId, markdown);

        assertThat(elements).extracting(KnowledgeElement::type)
                .containsExactly(
                        ElementType.PARAGRAPH,
                        ElementType.HEADING,
                        ElementType.PARAGRAPH,
                        ElementType.HEADING,
                        ElementType.LIST,
                        ElementType.TABLE,
                        ElementType.CODE
                );
        assertThat(elements.getFirst().attributes()).containsEntry("role", "FRONT_MATTER");
        assertThat(elements.getFirst().content()).contains("confidential: true");
        assertThat(elements.get(3).sectionPath()).containsExactly("用户中心", "诊断");
        assertThat(elements.get(6).attributes()).containsEntry("language", "java");
    }

    @Test
    void createsDeterministicBoundedChunks() {
        UUID revisionId = UUID.randomUUID();
        String oversized = "长段落。".repeat(100);
        List<KnowledgeElement> elements = parse(revisionId, "# 排障\n\n" + oversized);
        var counter = new Utf8ByteBudgetTokenCounter();
        var chunker = new StructuralKnowledgeChunker(
                new ChunkSizing(counter, 32, 128, 180, 0)
        );

        List<KnowledgeChunk> first = chunker.chunk(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("ops"),
                new DocumentId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                revisionId,
                elements
        ).chunks();
        List<KnowledgeChunk> second = chunker.chunk(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("ops"),
                new DocumentId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                revisionId,
                elements
        ).chunks();

        assertThat(first).hasSizeGreaterThan(2);
        assertThat(first).allMatch(chunk -> counter.count(chunk.content()) <= 180);
        assertThat(first).extracting(KnowledgeChunk::id)
                .containsExactlyElementsOf(second.stream().map(KnowledgeChunk::id).toList());
        assertThat(first).allMatch(chunk -> chunk.sectionPath().contains("排障"));
    }

    private static List<KnowledgeElement> parse(UUID revisionId, String markdown) {
        return new MarkdownDocumentParser().parse(new DocumentParseInput(
                revisionId,
                "text/markdown",
                "knowledge.md",
                markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                DocumentParseLimits.defaults()
        )).elements();
    }
}
