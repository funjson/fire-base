package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeChunk;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class StructuralKnowledgeChunkerTest {
    private static final TenantId TENANT_ID = new TenantId("tenant-a");
    private static final KnowledgeSpaceId SPACE_ID = new KnowledgeSpaceId("ops");
    private static final DocumentId DOCUMENT_ID = new DocumentId(
            UUID.fromString("10000000-0000-0000-0000-000000000001")
    );

    @Test
    void exposesSizingAndTokenizerInContractAndReturnsSeparateDiagnostics() {
        UUID revisionId = UUID.randomUUID();
        var chunker = chunker(8, 32, 64, 0);

        ChunkingResult result = chunker.chunk(
                TENANT_ID,
                SPACE_ID,
                DOCUMENT_ID,
                revisionId,
                List.of(element(revisionId, 0, ElementType.PARAGRAPH, "正文"))
        );

        assertThat(chunker.contract())
                .contains("provider=STRUCTURAL")
                .contains("tokenizer=UTF8_BYTE_BUDGET")
                .contains("minimum=8:target=32:maximum=64:overlap=0");
        assertThat(result.chunks()).singleElement().satisfies(chunk ->
                assertThat(chunk.metadata())
                        .containsEntry("chunker", ChunkAssembler.VERSION)
                        .containsEntry("boundaryProvider", "STRUCTURAL")
                        .doesNotContainKeys("structuralHardBreaks", "finalChunkCount")
        );
        assertThat(result.diagnostics().finalChunkCount()).isEqualTo(1);
        assertThat(result.diagnostics().minimumChunkUnits()).isPositive();
    }

    @Test
    void rejectsOutOfOrderOrDuplicateElementOrdinals() {
        UUID revisionId = UUID.randomUUID();
        var chunker = chunker(8, 32, 64, 0);
        KnowledgeElement first = element(revisionId, 0, ElementType.PARAGRAPH, "first");
        KnowledgeElement second = element(revisionId, 1, ElementType.PARAGRAPH, "second");
        KnowledgeElement duplicate = element(revisionId, 0, ElementType.PARAGRAPH, "duplicate");

        assertThatThrownBy(() -> chunker.chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(second, first)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");
        assertThatThrownBy(() -> chunker.chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(first, duplicate)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void rejectsDuplicateElementIdsAtTheSingleSlicingBoundary() {
        UUID revisionId = UUID.randomUUID();
        UUID duplicateId = UUID.randomUUID();
        KnowledgeElement first = new KnowledgeElement(
                duplicateId, revisionId, null, ElementType.PARAGRAPH, 0,
                List.of(), "first", Map.of()
        );
        KnowledgeElement second = new KnowledgeElement(
                duplicateId, revisionId, null, ElementType.PARAGRAPH, 1,
                List.of(), "second", Map.of()
        );

        assertThatThrownBy(() -> chunker(1, 16, 32, 0).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(first, second)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ids must be unique");
    }

    @Test
    void createsSentenceSlicesAndPreservesExactOffsets() {
        UUID revisionId = UUID.randomUUID();
        UUID elementId = UUID.randomUUID();
        KnowledgeElement paragraph = new KnowledgeElement(
                elementId,
                revisionId,
                null,
                ElementType.PARAGRAPH,
                0,
                List.of("Runbook"),
                "First. Second.",
                Map.of("pageNumber", "2")
        );

        KnowledgeChunk chunk = chunker(4, 64, 128, 0).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(paragraph)
        ).chunks().getFirst();

        assertThat(chunk.content()).isEqualTo("First. Second.");
        assertThat(chunk.contextualText()).isEqualTo("Runbook\n\nFirst. Second.");
        assertThat(chunk.elementIds()).containsExactly(elementId);
        assertThat(chunk.sourceSpans()).containsExactly(
                new ChunkSourceSpan(elementId, 0, 6, 2),
                new ChunkSourceSpan(elementId, 6, 14, 2)
        );
    }

    @Test
    void preservesTableAndSentenceSeparatorsWithoutLosingSourceCharacters() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeElement paragraph = element(
                revisionId, 0, ElementType.PARAGRAPH, "First.  Second.\nThird."
        );
        KnowledgeElement table = element(
                revisionId, 1, ElementType.TABLE, "a|b\n-| -\n1|2"
        );

        ChunkingResult result = chunker(1, 256, 512, 0).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(paragraph, table)
        );

        assertThat(result.chunks()).extracting(KnowledgeChunk::content)
                .containsExactly(paragraph.content(), table.content());
        assertThat(result.chunks().getFirst().sourceSpans())
                .extracting(ChunkSourceSpan::startOffset, ChunkSourceSpan::endOffset)
                .containsExactly(tuple(0, 6), tuple(6, 15), tuple(15, 22));
        assertThat(result.chunks().get(1).content()).doesNotContain("\n\n");
    }

    @Test
    void appliesTokenHardLimitToOversizedHeadingAndKeepsOffsets() {
        UUID revisionId = UUID.randomUUID();
        UUID headingId = UUID.randomUUID();
        String oversized = "x".repeat(250);
        KnowledgeElement heading = new KnowledgeElement(
                headingId, revisionId, null, ElementType.HEADING, 0,
                List.of("Runbook"), oversized, Map.of("pageNumber", "3")
        );

        ChunkingResult result = chunker(20, 80, 100, 0).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(heading)
        );

        assertThat(result.chunks()).hasSize(3)
                .allSatisfy(chunk -> assertThat(new Utf8ByteBudgetTokenCounter()
                        .count(chunk.contextualText())).isLessThanOrEqualTo(100));
        assertThat(result.chunks().stream().flatMap(chunk -> chunk.sourceSpans().stream()))
                .extracting(
                        ChunkSourceSpan::startOffset,
                        ChunkSourceSpan::endOffset,
                        ChunkSourceSpan::pageNumber
                )
                .containsExactly(
                        tuple(0, 91, 3),
                        tuple(91, 182, 3),
                        tuple(182, 250, 3)
                );
        assertThat(result.diagnostics().structuralHardBreaks()).isEqualTo(2);
    }

    @Test
    void groupsRowsInsideOneTableButIsolatesTableCodeAndAttachmentFromNeighbors() {
        UUID revisionId = UUID.randomUUID();
        List<KnowledgeElement> elements = List.of(
                element(revisionId, 0, ElementType.PARAGRAPH, "before"),
                element(revisionId, 1, ElementType.TABLE, "row-1\nrow-2"),
                element(revisionId, 2, ElementType.CODE, "call();"),
                element(revisionId, 3, ElementType.ATTACHMENT, "manual.pdf"),
                element(revisionId, 4, ElementType.PARAGRAPH, "after")
        );

        ChunkingResult result = chunker(4, 128, 256, 0).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, elements
        );

        assertThat(result.chunks()).extracting(KnowledgeChunk::content)
                .containsExactly(
                        "before",
                        "row-1\nrow-2",
                        "call();",
                        "manual.pdf",
                        "after"
                );
        assertThat(result.chunks().get(1).sourceSpans()).hasSize(2);
        assertThat(result.diagnostics().structuralHardBreaks()).isEqualTo(4);
    }

    @Test
    void oversizedCodeUsesLineThenCodePointLimitsWithoutParagraphPunctuationRule() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeElement code = element(
                revisionId,
                0,
                ElementType.CODE,
                "obj.method();\nnextCall();"
        );

        ChunkingResult result = chunker(1, 6, 8, 0).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(code)
        );

        assertThat(result.chunks().getFirst().content()).isEqualTo("obj.meth");
        assertThat(result.chunks())
                .allSatisfy(chunk -> assertThat(new Utf8ByteBudgetTokenCounter()
                        .count(chunk.contextualText())).isLessThanOrEqualTo(8));
        assertThat(result.chunks().stream().map(KnowledgeChunk::content)
                .collect(java.util.stream.Collectors.joining())).isEqualTo(code.content());
    }

    @Test
    void contextualBudgetSplitsLongTextLinearlyAndDoesNotDuplicateHeading() {
        UUID revisionId = UUID.randomUUID();
        TrackingTokenCounter counter = new TrackingTokenCounter();
        String content = "x".repeat(10_000);
        KnowledgeElement paragraph = new KnowledgeElement(
                UUID.randomUUID(), revisionId, null, ElementType.PARAGRAPH, 0,
                List.of("Runbook"), content, Map.of()
        );
        StructuralKnowledgeChunker chunker = new StructuralKnowledgeChunker(new ChunkSizing(
                counter, 8, 48, 64, 0
        ));

        ChunkingResult result = chunker.chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(paragraph)
        );

        assertThat(result.chunks())
                .allSatisfy(chunk -> assertThat(counter.count(chunk.contextualText()))
                        .isLessThanOrEqualTo(64));
        assertThat(result.chunks().stream().map(KnowledgeChunk::content)
                .collect(java.util.stream.Collectors.joining())).isEqualTo(content);
        assertThat(counter.maximumCountInputLength()).isLessThanOrEqualTo(73);
        assertThat(counter.prefixCodePointsVisited()).isLessThan(content.length() * 3L);

        KnowledgeElement heading = new KnowledgeElement(
                UUID.randomUUID(), revisionId, null, ElementType.HEADING, 1,
                List.of("Root", "Runbook"), "Runbook", Map.of()
        );
        KnowledgeChunk headingChunk = chunker(1, 32, 64, 0).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, List.of(heading)
        ).chunks().getFirst();
        assertThat(headingChunk.contextualText()).isEqualTo("Root\n\nRunbook");
    }

    @Test
    void addsWholeSliceOverlapAcrossSizeBoundaryButNeverPartialText() {
        UUID revisionId = UUID.randomUUID();
        List<KnowledgeElement> elements = List.of(
                element(revisionId, 0, ElementType.PARAGRAPH, "aaaaa"),
                element(revisionId, 1, ElementType.PARAGRAPH, "bbbbb"),
                element(revisionId, 2, ElementType.PARAGRAPH, "ccccc")
        );

        ChunkingResult withOverlap = chunker(7, 14, 30, 6).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, elements
        );
        ChunkingResult withoutEnoughBudget = chunker(5, 14, 30, 4).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, elements
        );

        assertThat(withOverlap.chunks()).extracting(KnowledgeChunk::content)
                .containsExactly("aaaaa\n\nbbbbb", "bbbbb\n\nccccc");
        assertThat(withOverlap.chunks().get(1).sourceSpans().getFirst().elementId())
                .isEqualTo(elements.get(1).id());
        assertThat(withoutEnoughBudget.chunks()).extracting(KnowledgeChunk::content)
                .containsExactly("aaaaa\n\nbbbbb", "ccccc");
    }

    @Test
    void neverCarriesOverlapAcrossStructuralBoundary() {
        UUID revisionId = UUID.randomUUID();
        List<KnowledgeElement> elements = List.of(
                element(revisionId, 0, ElementType.PARAGRAPH, "aaaaa"),
                element(revisionId, 1, ElementType.CODE, "code")
        );

        ChunkingResult result = chunker(6, 8, 30, 5).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, elements
        );

        assertThat(result.chunks()).extracting(KnowledgeChunk::content)
                .containsExactly("aaaaa", "code");
    }

    @Test
    void flushesBeforeTheProjectionSafeSourceSpanLimit() {
        UUID revisionId = UUID.randomUUID();
        List<KnowledgeElement> elements = IntStream.range(0, 129)
                .mapToObj(index -> element(
                        revisionId, index, ElementType.PARAGRAPH, "x"
                ))
                .toList();

        ChunkingResult result = chunker(2, 10_000, 20_000, 1).chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, elements
        );

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().getFirst().sourceSpans()).hasSize(128);
        assertThat(result.chunks().get(1).sourceSpans()).hasSize(1);
        assertThat(result.diagnostics().spanLimitBreaks()).isEqualTo(1);
    }

    @Test
    void producesStableChunkIdsForTheSameRevisionAndPlan() {
        UUID revisionId = UUID.randomUUID();
        List<KnowledgeElement> elements = List.of(
                element(revisionId, 0, ElementType.PARAGRAPH, "First. Second."),
                element(revisionId, 1, ElementType.PARAGRAPH, "Third.")
        );
        var chunker = chunker(4, 12, 30, 0);

        List<UUID> first = chunker.chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, elements
        ).chunks().stream().map(KnowledgeChunk::id).toList();
        List<UUID> second = chunker.chunk(
                TENANT_ID, SPACE_ID, DOCUMENT_ID, revisionId, elements
        ).chunks().stream().map(KnowledgeChunk::id).toList();

        assertThat(first).containsExactlyElementsOf(second);
    }

    private static StructuralKnowledgeChunker chunker(
            int minimum,
            int target,
            int maximum,
            int overlap
    ) {
        return new StructuralKnowledgeChunker(new ChunkSizing(
                new Utf8ByteBudgetTokenCounter(),
                minimum,
                target,
                maximum,
                overlap
        ));
    }

    private static KnowledgeElement element(
            UUID revisionId,
            int ordinal,
            ElementType type,
            String content
    ) {
        return new KnowledgeElement(
                UUID.nameUUIDFromBytes((revisionId + ":" + ordinal).getBytes()),
                revisionId,
                null,
                type,
                ordinal,
                List.of(),
                content,
                Map.of()
        );
    }

    /** 记录 Slicer 是否只对有界候选做正文计数的测试预算器。 */
    private static final class TrackingTokenCounter implements TokenCounter {
        private int maximumCountInputLength;
        private long prefixCodePointsVisited;

        @Override
        public String id() {
            return "test.codepoint:v1";
        }

        @Override
        public String version() {
            return "v1";
        }

        @Override
        public String description() {
            return "测试用 Unicode Code Point 预算计数器";
        }

        @Override
        public boolean exactModelTokens() {
            return false;
        }

        @Override
        public int count(String text) {
            maximumCountInputLength = Math.max(maximumCountInputLength, text.length());
            return text.codePointCount(0, text.length());
        }

        @Override
        public int maximumPrefixEnd(
                String text,
                int startOffset,
                int endOffset,
                int maximumTokens
        ) {
            int index = startOffset;
            int used = 0;
            while (index < endOffset && used < maximumTokens) {
                index += Character.charCount(text.codePointAt(index));
                used++;
                prefixCodePointsVisited++;
            }
            return index;
        }

        int maximumCountInputLength() {
            return maximumCountInputLength;
        }

        long prefixCodePointsVisited() {
            return prefixCodePointsVisited;
        }
    }
}
