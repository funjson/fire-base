package dev.infinityknowledge.ingestion.cleaning;

import dev.infinityknowledge.domain.document.DocumentId;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.chunking.ChunkSizing;
import dev.infinityknowledge.ingestion.chunking.StructuralKnowledgeChunker;
import dev.infinityknowledge.ingestion.chunking.Utf8ByteBudgetTokenCounter;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningConfiguration.Action;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.ParsedDocument;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicDocumentCleaningPolicyTest {

    private final DeterministicDocumentCleaningPolicy policy =
            new DeterministicDocumentCleaningPolicy();

    @Test
    void keepsExistingIndexableBehaviorByDefaultButAlwaysRemovesHiddenElements() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeElement header = element(revisionId, 0, "页眉", "HEADER");
        KnowledgeElement body = element(revisionId, 1, "正文", null);
        KnowledgeElement hidden = element(revisionId, 2, "不可索引内容", "HIDDEN");
        KnowledgeElement unknown = element(revisionId, 3, "供应商扩展", "CUSTOM_ROLE");

        DocumentCleaningResult result = policy.clean(
                parsed(header, body, hidden, unknown),
                DocumentCleaningConfiguration.defaults()
        );

        assertThat(result.indexableElements()).containsExactly(header, body, unknown);
        assertThat(result.indexableElements().getFirst()).isSameAs(header);
        assertThat(result.indexableElements().get(1)).isSameAs(body);
        assertThat(result.metadataOnlyElements()).isEmpty();
        assertThat(result.reasonCodeCounts()).containsExactly(
                Map.entry("HEADER_KEPT", 1),
                Map.entry("HIDDEN_REMOVED", 1)
        );
    }

    @Test
    void separatesConfiguredMetadataAndMarksItsElementWithStableGovernanceAttributes() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeElement header = element(revisionId, 0, "页眉", " header ");
        KnowledgeElement footer = element(revisionId, 1, "页脚", "FOOTER");
        KnowledgeElement pageNumber = element(revisionId, 2, "— 12 —", "page_number");
        KnowledgeElement watermark = element(revisionId, 3, "内部资料", "WATERMARK");
        KnowledgeElement frontMatter = element(revisionId, 4, "owner: platform", "FRONT_MATTER");
        KnowledgeElement body = element(revisionId, 5, "真正正文", null);
        var configuration = new DocumentCleaningConfiguration(
                Action.REMOVE,
                Action.METADATA_ONLY,
                Action.REMOVE,
                Action.METADATA_ONLY,
                Action.KEEP
        );

        DocumentCleaningResult result = policy.clean(
                parsed(header, footer, pageNumber, watermark, frontMatter, body),
                configuration
        );

        assertThat(result.indexableElements()).containsExactly(frontMatter, body);
        assertThat(result.indexableElements().getFirst()).isSameAs(frontMatter);
        assertThat(result.metadataOnlyElements())
                .extracting(KnowledgeElement::id)
                .containsExactly(footer.id(), watermark.id());
        assertThat(result.metadataOnlyElements().getFirst()).isNotSameAs(footer);
        assertThat(result.metadataOnlyElements().getFirst())
                .extracting(
                        KnowledgeElement::content,
                        KnowledgeElement::sectionPath,
                        KnowledgeElement::ordinal
                )
                .containsExactly(footer.content(), footer.sectionPath(), footer.ordinal());
        assertThat(result.metadataOnlyElements().getFirst().attributes())
                .containsEntry("cleaningDisposition", "METADATA_ONLY")
                .containsEntry("cleaningReasonCode", "FOOTER_METADATA_ONLY")
                .containsEntry("disposition", "PARSER_VALUE")
                .containsEntry("reasonCode", "PARSER_REASON")
                .containsEntry("pageNumber", "5")
                .containsEntry("bbox", "10,20,30,40");
        assertThat(result.reasonCodeCounts()).containsExactly(
                Map.entry("HEADER_REMOVED", 1),
                Map.entry("FOOTER_METADATA_ONLY", 1),
                Map.entry("PAGE_NUMBER_REMOVED", 1),
                Map.entry("WATERMARK_METADATA_ONLY", 1),
                Map.entry("FRONT_MATTER_KEPT", 1)
        );
        assertThat(result.indexableElements().getFirst().attributes())
                .containsEntry("pageNumber", "5")
                .containsEntry("bbox", "10,20,30,40");
    }

    @Test
    void contractDependsOnlyOnRuleVersionAndConfiguration() {
        var configuration = new DocumentCleaningConfiguration(
                Action.REMOVE,
                Action.METADATA_ONLY,
                Action.KEEP,
                Action.REMOVE,
                Action.METADATA_ONLY
        );

        String contract = policy.contract(configuration);
        DocumentCleaningResult first = policy.clean(
                parsed(element(UUID.randomUUID(), 0, "A", "HEADER")),
                configuration
        );
        DocumentCleaningResult second = policy.clean(
                parsed(element(UUID.randomUUID(), 0, "B", null)),
                configuration
        );

        assertThat(contract).isEqualTo(
                "deterministic-cleaning-v2:h=REMOVE,f=METADATA_ONLY,p=KEEP,"
                        + "w=REMOVE,fm=METADATA_ONLY,hidden=REMOVE,blank=REMOVE"
        );
        assertThat(first.contract()).isEqualTo(contract);
        assertThat(second.contract()).isEqualTo(contract);
        assertThat(first.reasonCodeCounts()).isNotEqualTo(second.reasonCodeCounts());
    }

    @Test
    void mapsEveryPersistedSpaceCleaningChoiceToRuntimeConfiguration() {
        var persisted = new SpaceDocumentProcessingConfigStore.CleaningConfiguration(
                CleaningAction.REMOVE,
                CleaningAction.METADATA_ONLY,
                CleaningAction.KEEP,
                CleaningAction.REMOVE,
                CleaningAction.METADATA_ONLY
        );

        DocumentCleaningConfiguration runtime =
                DocumentCleaningConfiguration.from(persisted);

        assertThat(runtime).isEqualTo(new DocumentCleaningConfiguration(
                Action.REMOVE,
                Action.METADATA_ONLY,
                Action.KEEP,
                Action.REMOVE,
                Action.METADATA_ONLY
        ));
    }

    @Test
    void excludedTitleAndHeadingDoNotLeakIntoLaterContextualSectionPaths() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeElement removedTitle = element(
                revisionId,
                0,
                null,
                ElementType.TITLE,
                List.of("机密"),
                "机密",
                "WATERMARK"
        );
        KnowledgeElement removedHeading = element(
                revisionId,
                1,
                removedTitle.id(),
                ElementType.HEADING,
                List.of("机密", "废弃章节"),
                "废弃章节",
                "WATERMARK"
        );
        KnowledgeElement metadataHeading = element(
                revisionId,
                2,
                removedHeading.id(),
                ElementType.HEADING,
                List.of("机密", "废弃章节", "内部章节"),
                "内部章节",
                "HEADER"
        );
        KnowledgeElement nestedBody = element(
                revisionId,
                3,
                metadataHeading.id(),
                ElementType.PARAGRAPH,
                List.of("机密", "废弃章节", "内部章节"),
                "可公开正文",
                null
        );
        KnowledgeElement publicHeading = element(
                revisionId,
                4,
                null,
                ElementType.HEADING,
                List.of("公开章节"),
                "公开章节",
                null
        );
        KnowledgeElement publicBody = element(
                revisionId,
                5,
                publicHeading.id(),
                ElementType.PARAGRAPH,
                List.of("公开章节"),
                "普通正文",
                null
        );
        var configuration = new DocumentCleaningConfiguration(
                Action.METADATA_ONLY,
                Action.KEEP,
                Action.KEEP,
                Action.REMOVE,
                Action.KEEP
        );

        DocumentCleaningResult result = policy.clean(
                parsed(
                        removedTitle,
                        removedHeading,
                        metadataHeading,
                        nestedBody,
                        publicHeading,
                        publicBody
                ),
                configuration
        );

        assertThat(result.indexableElements())
                .extracting(KnowledgeElement::id)
                .containsExactly(nestedBody.id(), publicHeading.id(), publicBody.id());
        assertThat(result.indexableElements())
                .extracting(KnowledgeElement::sectionPath)
                .containsExactly(
                        List.of(),
                        List.of("公开章节"),
                        List.of("公开章节")
                );
        assertThat(result.metadataOnlyElements()).hasSize(1);
        KnowledgeElement governed = result.metadataOnlyElements().getFirst();
        assertThat(governed.id()).isEqualTo(metadataHeading.id());
        assertThat(governed.parentId()).isNull();
        assertThat(governed.content()).isEqualTo(metadataHeading.content());
        assertThat(governed.sectionPath()).isEmpty();
        assertThat(governed.attributes())
                .containsEntry("cleaningDisposition", "METADATA_ONLY")
                .containsEntry("cleaningReasonCode", "HEADER_METADATA_ONLY")
                .containsEntry("disposition", "PARSER_VALUE")
                .containsEntry("reasonCode", "PARSER_REASON")
                .containsEntry("pageNumber", "5")
                .containsEntry("bbox", "10,20,30,40");
        assertThat(result.indexableElements().getFirst().parentId())
                .isEqualTo(metadataHeading.id());
        assertThat(result.indexableElements().getLast().parentId())
                .isEqualTo(publicHeading.id());
        assertThat(result.indexableElements())
                .flatExtracting(KnowledgeElement::sectionPath)
                .doesNotContain("机密", "废弃章节", "内部章节");
        var chunks = new StructuralKnowledgeChunker(new ChunkSizing(
                new Utf8ByteBudgetTokenCounter(), 32, 128, 512, 0
        )).chunk(
                new TenantId("tenant-a"),
                new KnowledgeSpaceId("space-a"),
                new DocumentId(UUID.randomUUID()),
                revisionId,
                result.indexableElements()
        ).chunks();
        assertThat(chunks)
                .extracting(chunk -> chunk.contextualText())
                .allSatisfy(contextualText -> assertThat(contextualText)
                        .doesNotContain("机密", "废弃章节", "内部章节"));
    }

    @Test
    void rejectsParserTreeWhoseParentDoesNotPrecedeItsChild() {
        UUID revisionId = UUID.randomUUID();
        KnowledgeElement parent = element(
                revisionId,
                1,
                ElementType.HEADING,
                List.of("章节"),
                "章节",
                null
        );
        KnowledgeElement child = element(
                revisionId,
                0,
                parent.id(),
                ElementType.PARAGRAPH,
                List.of("章节"),
                "正文",
                null
        );

        assertThatThrownBy(() -> policy.clean(
                parsed(child, parent),
                DocumentCleaningConfiguration.defaults()
        )).isInstanceOf(DocumentParseException.class)
                .hasMessage("element parent must precede its child in parser output");
    }

    private static ParsedDocument parsed(KnowledgeElement... elements) {
        return new ParsedDocument("test-parser", "test-v1", List.of(elements), Map.of());
    }

    private static KnowledgeElement element(
            UUID revisionId,
            int ordinal,
            String content,
            String role
    ) {
        return element(
                revisionId,
                ordinal,
                ElementType.PARAGRAPH,
                List.of("章节"),
                content,
                role
        );
    }

    private static KnowledgeElement element(
            UUID revisionId,
            int ordinal,
            ElementType type,
            List<String> sectionPath,
            String content,
            String role
    ) {
        return element(
                revisionId,
                ordinal,
                null,
                type,
                sectionPath,
                content,
                role
        );
    }

    private static KnowledgeElement element(
            UUID revisionId,
            int ordinal,
            UUID parentId,
            ElementType type,
            List<String> sectionPath,
            String content,
            String role
    ) {
        Map<String, String> attributes = role == null
                ? Map.of(
                        "pageNumber", "5",
                        "bbox", "10,20,30,40",
                        "disposition", "PARSER_VALUE",
                        "reasonCode", "PARSER_REASON"
                )
                : Map.of(
                        "role", role,
                        "pageNumber", "5",
                        "bbox", "10,20,30,40",
                        "disposition", "PARSER_VALUE",
                        "reasonCode", "PARSER_REASON"
                );
        return new KnowledgeElement(
                UUID.randomUUID(),
                revisionId,
                parentId,
                type,
                ordinal,
                sectionPath,
                content,
                attributes
        );
    }
}
