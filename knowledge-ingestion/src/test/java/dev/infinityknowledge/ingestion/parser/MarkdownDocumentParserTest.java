package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownDocumentParserTest {

    @Test
    void parsesMarkdownThroughTheSingleParserImplementation() {
        UUID revisionId = UUID.randomUUID();
        String markdown = """
                ---
                owner: platform
                ---
                # 用户中心

                ## 排障

                - 检查网关
                - 检查 Redis

                ```java
                callGateway();
                ```
                """;
        byte[] source = markdown.getBytes(StandardCharsets.UTF_8);

        ParsedDocument parsed = new MarkdownDocumentParser().parse(new DocumentParseInput(
                revisionId,
                "text/markdown",
                "runbook.md",
                source,
                DocumentParseLimits.defaults()
        ));
        assertThat(parsed.parserId()).isEqualTo("markdown-structure");
        assertThat(parsed.parserVersion()).isEqualTo(MarkdownDocumentParser.VERSION);
        assertThat(parsed.elements()).extracting(KnowledgeElement::type)
                .containsExactly(
                        ElementType.PARAGRAPH,
                        ElementType.HEADING,
                        ElementType.HEADING,
                        ElementType.LIST,
                        ElementType.CODE
                );
        assertThat(parsed.elements().getFirst().attributes())
                .containsEntry("role", "FRONT_MATTER");
        assertThat(parsed.elements().getFirst().content()).isEqualTo("owner: platform");
        assertThat(parsed.elements().get(3).sectionPath())
                .containsExactly("用户中心", "排障");

        var cleaned = new DeterministicDocumentCleaningPolicy().clean(
                parsed,
                DocumentCleaningConfiguration.defaults()
        );
        assertThat(cleaned.indexableElements())
                .extracting(KnowledgeElement::type)
                .containsExactly(
                        ElementType.HEADING,
                        ElementType.HEADING,
                        ElementType.LIST,
                        ElementType.CODE
                );
        assertThat(cleaned.reasonCodeCounts())
                .containsEntry("FRONT_MATTER_REMOVED", 1);
    }

    @Test
    void standardRegistrySelectsMarkdownByExtension() {
        UUID revisionId = UUID.randomUUID();
        byte[] source = "# 标题\n\n正文".getBytes(StandardCharsets.UTF_8);

        DocumentParserRegistry registry = DocumentParserRegistry.standard();
        ParsedDocument parsed = registry.select(
                "application/octet-stream",
                "knowledge.md",
                registry.defaultParserSelections()
        ).parse(
                revisionId,
                "knowledge.md",
                source,
                DocumentParseLimits.defaults()
        );

        assertThat(parsed.parserId()).isEqualTo("markdown-structure");
        assertThat(parsed.elements()).hasSize(2);
    }

    @Test
    void skipsEmptyClosedFrontMatterWithoutCreatingBlankElement() {
        byte[] source = "---\n---\n# 标题\n\n正文".getBytes(StandardCharsets.UTF_8);

        ParsedDocument parsed = new MarkdownDocumentParser().parse(new DocumentParseInput(
                UUID.randomUUID(),
                "text/markdown",
                "knowledge.md",
                source,
                DocumentParseLimits.defaults()
        ));

        assertThat(parsed.elements())
                .extracting(KnowledgeElement::type)
                .containsExactly(ElementType.HEADING, ElementType.PARAGRAPH);
        assertThat(parsed.elements())
                .noneMatch(element -> "FRONT_MATTER".equals(
                        element.attributes().get("role")
                ));
    }

    @Test
    void appliesSharedElementBudget() {
        byte[] source = "# 标题\n\n正文".getBytes(StandardCharsets.UTF_8);
        var limits = new DocumentParseLimits(
                1_024,
                2_048,
                10,
                1,
                1_000,
                10,
                10
        );

        assertThatThrownBy(() -> new MarkdownDocumentParser().parse(new DocumentParseInput(
                UUID.randomUUID(),
                "text/markdown",
                "knowledge.md",
                source,
                limits
        ))).isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("maximumElements");
    }
}
