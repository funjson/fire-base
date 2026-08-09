package dev.infinityknowledge.ingestion;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownIngestionTest {

    @Test
    void preservesStructureAndSkipsFrontMatter() {
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

        List<KnowledgeElement> elements = new MarkdownElementParser().parse(revisionId, markdown);

        assertThat(elements).extracting(KnowledgeElement::type)
                .containsExactly(
                        ElementType.HEADING,
                        ElementType.PARAGRAPH,
                        ElementType.HEADING,
                        ElementType.LIST,
                        ElementType.TABLE,
                        ElementType.CODE
                );
        assertThat(elements.get(2).sectionPath()).containsExactly("用户中心", "诊断");
        assertThat(elements.get(5).attributes()).containsEntry("language", "java");
        assertThat(elements).noneMatch(element -> element.content().contains("confidential"));
    }

    @Test
    void createsDeterministicBoundedChunks() {
        UUID revisionId = UUID.randomUUID();
        String oversized = "长段落。".repeat(100);
        List<KnowledgeElement> elements = new MarkdownElementParser().parse(
                revisionId,
                "# 排障\n\n" + oversized
        );
        HeadingAwareChunker chunker = new HeadingAwareChunker(128, 180);

        List<KnowledgeChunk> first = chunker.chunk(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("ops"),
                new DocumentId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                revisionId,
                elements
        );
        List<KnowledgeChunk> second = chunker.chunk(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("ops"),
                new DocumentId(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                revisionId,
                elements
        );

        assertThat(first).hasSizeGreaterThan(2);
        assertThat(first).allMatch(chunk -> chunk.content().length() <= 180);
        assertThat(first).extracting(KnowledgeChunk::id)
                .containsExactlyElementsOf(second.stream().map(KnowledgeChunk::id).toList());
        assertThat(first).allMatch(chunk -> chunk.sectionPath().contains("排障"));
    }
}
