package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.controlplane.config.IngestionProperties;
import dev.infinityknowledge.controlplane.config.ingestion.DoclingParserProperties;
import dev.infinityknowledge.controlplane.config.ingestion.HuggingFaceTokenizerProperties;
import dev.infinityknowledge.ingestion.chunking.ChunkBoundaryAdvice;
import dev.infinityknowledge.ingestion.chunking.ChunkBoundaryStrategy;
import dev.infinityknowledge.ingestion.chunking.ElementSlice;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerProvider;
import dev.infinityknowledge.ingestion.chunking.TokenCounter;
import dev.infinityknowledge.ingestion.parser.MarkdownDocumentParser;
import dev.infinityknowledge.ingestion.parser.DocxDocumentParser;
import dev.infinityknowledge.ingestion.parser.DocumentParser;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.ingestion.parser.HtmlDocumentParser;
import dev.infinityknowledge.ingestion.parser.PdfDocumentParser;
import dev.infinityknowledge.ingestion.parser.ParserOutputCapability;
import dev.infinityknowledge.ingestion.parser.PlainTextDocumentParser;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证页面选项只暴露部署真正可执行的摄取能力。 */
class InstalledDocumentProcessingCapabilitiesTest {

    @Test
    void exposesFiveProductionParsersAndBothBuiltInChunkers() {
        var registry = new DocumentParserRegistry(List.of(
                new MarkdownDocumentParser(),
                new PlainTextDocumentParser(),
                new HtmlDocumentParser(),
                new PdfDocumentParser(),
                new DocxDocumentParser()
        ));

        var snapshot = capabilities(
                registry,
                new KnowledgeChunkerFactory(List.of(), List.of())
        ).snapshot();

        assertEquals(5, snapshot.parsers().size());
        assertEquals(5, snapshot.parsers().stream()
                .map(DocumentProcessingCapabilities.ParserCapability::canonicalMediaType)
                .distinct()
                .count());
        assertEquals(5, snapshot.parsers().stream()
                .filter(DocumentProcessingCapabilities.ParserCapability::defaultSelection)
                .count());
        assertEquals(2, snapshot.chunkers().size());
    }

    @Test
    void rejectsDeploymentWhoseDefaultConfigCannotCoverInstalledFormats() {
        DocumentParser first = parser("plain-a");
        DocumentParser second = parser("plain-b");
        DocumentParserRegistry registry = new DocumentParserRegistry(
                List.of(first, second)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> capabilities(
                        registry,
                        new KnowledgeChunkerFactory(List.of(), List.of())
                )
        );
    }

    @Test
    void fallsBackToBaselineWhenSemanticCapabilityIsDisabled() {
        var capabilities = capabilities();

        var snapshot = capabilities.snapshot();

        assertEquals(
                Map.of("text/markdown", "markdown-structure"),
                snapshot.defaultParserSelections()
        );
        assertEquals(
                List.of("FLAT_TABLE_TEXT", "HIERARCHY", "STANDARD_ELEMENTS"),
                snapshot.parsers().getFirst().outputCapabilities()
        );
        assertEquals("text/markdown", snapshot.parsers().getFirst()
                .canonicalMediaType());
        assertEquals(true, snapshot.parsers().getFirst().defaultSelection());
        assertEquals(
                KnowledgeChunkerFactory.STRUCTURAL,
                snapshot.defaultChunker().providerId()
        );
        var semantic = snapshot.chunkers().stream()
                .filter(value -> value.id().equals(
                        KnowledgeChunkerFactory.SEMANTIC_REFINEMENT
                ))
                .findFirst()
                .orElseThrow();
        assertFalse(semantic.available());
        assertEquals(
                "SEMANTIC_REFINEMENT_DISABLED",
                semantic.unavailableReason()
        );
        assertEquals(
                List.of("STANDARD_ELEMENTS"),
                semantic.requiredParserCapabilities()
        );
        assertEquals("UTF8_BYTE_BUDGET", snapshot.tokenizers().getFirst().id());
        assertFalse(snapshot.tokenizers().getFirst().exactModelTokens());
    }

    @Test
    void rejectsParserThatDoesNotBelongToRequestedFormat() {
        var capabilities = capabilities();
        var invalid = Map.of("text/markdown", "unknown-parser");

        assertThrows(
                IllegalArgumentException.class,
                () -> capabilities.validate(invalid, capabilities.snapshot().defaultChunker())
        );
    }

    @Test
    void rejectsParserSnapshotThatOmitsAnInstalledCanonicalFormat() {
        var capabilities = capabilities(
                new DocumentParserRegistry(List.of(
                        new MarkdownDocumentParser(),
                        new PlainTextDocumentParser()
                )),
                new KnowledgeChunkerFactory(List.of(), List.of())
        );

        Map<String, String> frozenSelections = Map.of(
                "text/markdown",
                "markdown-structure"
        );
        capabilities.validate(
                frozenSelections,
                capabilities.snapshot().defaultChunker()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> capabilities.validateForCreation(
                        frozenSelections,
                        capabilities.snapshot().defaultChunker()
                )
        );
    }

    @Test
    void exposesDisabledDoclingAdaptersButRejectsTheirSelection() {
        var registry = new DocumentParserRegistry(List.of(
                new MarkdownDocumentParser(),
                new PlainTextDocumentParser(),
                new HtmlDocumentParser(),
                new PdfDocumentParser(),
                new DocxDocumentParser()
        ));
        var capabilities = capabilities(registry, doclingProperties(false, false));

        var disabled = capabilities.snapshot().parsers().stream()
                .filter(parser -> parser.id().startsWith("docling-serve-"))
                .toList();
        assertEquals(2, disabled.size());
        assertEquals(
                Set.of("DOCLING_PARSER_DISABLED"),
                disabled.stream().map(
                        DocumentProcessingCapabilities.ParserCapability::unavailableReason
                ).collect(java.util.stream.Collectors.toSet())
        );
        assertFalse(disabled.getFirst().available());
        assertThrows(
                IllegalArgumentException.class,
                () -> capabilities.validate(
                        Map.of("application/pdf", "docling-serve-pdf"),
                        capabilities.snapshot().defaultChunker()
                )
        );
    }

    @Test
    void keepsDoclingDocxUnavailableWhenOnlyPdfGateIsEnabled() {
        var registry = new DocumentParserRegistry(List.of(
                new PdfDocumentParser(),
                new DocxDocumentParser()
        ));
        var capabilities = capabilities(registry, doclingProperties(true, false));

        var docx = capabilities.snapshot().parsers().stream()
                .filter(parser -> parser.id().equals("docling-serve-docx"))
                .findFirst()
                .orElseThrow();

        assertFalse(docx.available());
        assertEquals("DOCLING_DOCX_PARSER_DISABLED", docx.unavailableReason());
    }

    @Test
    void exposesDisabledHuggingFaceTokenizerButRejectsItsSelection() {
        var beanFactory = new StaticListableBeanFactory();
        var capabilities = new InstalledDocumentProcessingCapabilities(
                new DocumentParserRegistry(List.of(new MarkdownDocumentParser())),
                new IngestionProperties(
                        "UTF8_BYTE_BUDGET",
                        128,
                        512,
                        1_024,
                        32,
                        List.of("https")
                ),
                new KnowledgeChunkerFactory(List.of(), List.of()),
                beanFactory.getBeanProvider(EmbeddingSpec.class),
                null,
                new HuggingFaceTokenizerProperties(
                        false,
                        "HUGGINGFACE_LOCAL",
                        "",
                        null,
                        "",
                        true
                )
        );
        var tokenizer = capabilities.snapshot().tokenizers().stream()
                .filter(value -> value.id().equals("HUGGINGFACE_LOCAL"))
                .findFirst()
                .orElseThrow();

        assertFalse(tokenizer.available());
        assertEquals("HUGGINGFACE_TOKENIZER_DISABLED", tokenizer.unavailableReason());
        assertThrows(
                IllegalArgumentException.class,
                () -> capabilities.validate(
                        capabilities.snapshot().defaultParserSelections(),
                        new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                                KnowledgeChunkerFactory.STRUCTURAL,
                                "HUGGINGFACE_LOCAL",
                                128,
                                512,
                                1_024,
                                32,
                                "{}"
                        )
                )
        );
    }

    @Test
    void exposesAndSelectsThirdProviderFromRegistry() {
        var capabilities = capabilities(
                new DocumentParserRegistry(List.of(new MarkdownDocumentParser())),
                new KnowledgeChunkerFactory(
                        List.of(new FakeStructureProvider()),
                        List.of()
                )
        );
        var fake = capabilities.snapshot().chunkers().stream()
                .filter(value -> value.id().equals("FAKE_STRUCTURE"))
                .findFirst()
                .orElseThrow();

        capabilities.validate(
                capabilities.snapshot().defaultParserSelections(),
                chunker("FAKE_STRUCTURE")
        );

        assertEquals("fake-structure-provider-v1", fake.version());
        assertEquals(
                List.of("HIERARCHY", "STANDARD_ELEMENTS"),
                fake.requiredParserCapabilities()
        );
    }

    @Test
    void rejectsProviderBeforeSaveWhenSelectedParserLacksRequiredCapability() {
        var capabilities = capabilities(
                new DocumentParserRegistry(List.of(new PlainTextDocumentParser())),
                new KnowledgeChunkerFactory(
                        List.of(new FakeStructureProvider()),
                        List.of()
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> capabilities.validate(
                        capabilities.snapshot().defaultParserSelections(),
                        chunker("FAKE_STRUCTURE")
                )
        );
    }

    @Test
    void rejectsExactTokenizerBoundToAnotherEmbeddingProfile() {
        var embeddingSpec = new EmbeddingSpec("zhipu", "embedding-3", 2_048);
        var chunkers = new KnowledgeChunkerFactory(
                List.of(),
                List.of(new FakeExactTokenCounter("another/model@1024"))
        );
        var capabilities = capabilities(
                new DocumentParserRegistry(List.of(new MarkdownDocumentParser())),
                chunkers,
                embeddingSpec
        );
        var tokenizer = capabilities.snapshot().tokenizers().stream()
                .filter(value -> value.id().equals("FAKE_EXACT"))
                .findFirst()
                .orElseThrow();

        assertFalse(tokenizer.available());
        assertEquals(
                "TOKENIZER_EMBEDDING_PROFILE_MISMATCH",
                tokenizer.unavailableReason()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> capabilities.validate(
                        capabilities.snapshot().defaultParserSelections(),
                        new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                                KnowledgeChunkerFactory.STRUCTURAL,
                                "FAKE_EXACT",
                                128,
                                512,
                                1_024,
                                32,
                                "{}"
                        )
                )
        );
    }

    @Test
    void acceptsExactTokenizerBoundToInstalledEmbeddingProfile() {
        var embeddingSpec = new EmbeddingSpec("zhipu", "embedding-3", 2_048);
        String profileId = "zhipu/embedding-3@2048";
        var chunkers = new KnowledgeChunkerFactory(
                List.of(),
                List.of(new FakeExactTokenCounter(profileId))
        );
        var capabilities = capabilities(
                new DocumentParserRegistry(List.of(new MarkdownDocumentParser())),
                chunkers,
                embeddingSpec
        );
        var tokenizer = capabilities.snapshot().tokenizers().stream()
                .filter(value -> value.id().equals("FAKE_EXACT"))
                .findFirst()
                .orElseThrow();

        assertEquals(true, tokenizer.available());
        capabilities.validate(
                capabilities.snapshot().defaultParserSelections(),
                new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                        KnowledgeChunkerFactory.STRUCTURAL,
                        "FAKE_EXACT",
                        128,
                        512,
                        1_024,
                        32,
                        "{}"
                )
        );
    }

    private static InstalledDocumentProcessingCapabilities capabilities() {
        return capabilities(
                new DocumentParserRegistry(List.of(new MarkdownDocumentParser())),
                new KnowledgeChunkerFactory(List.of(), List.of())
        );
    }

    private static InstalledDocumentProcessingCapabilities capabilities(
            DocumentParserRegistry registry,
            KnowledgeChunkerFactory chunkers
    ) {
        return capabilities(registry, chunkers, null);
    }

    private static InstalledDocumentProcessingCapabilities capabilities(
            DocumentParserRegistry registry,
            KnowledgeChunkerFactory chunkers,
            EmbeddingSpec embeddingSpec
    ) {
        var beanFactory = new StaticListableBeanFactory();
        if (embeddingSpec != null) {
            beanFactory.addBean("embeddingSpec", embeddingSpec);
        }
        return new InstalledDocumentProcessingCapabilities(
                registry,
                new IngestionProperties(
                        "UTF8_BYTE_BUDGET",
                        128,
                        512,
                        1_024,
                        32,
                        List.of("https")
                ),
                chunkers,
                beanFactory.getBeanProvider(EmbeddingSpec.class)
        );
    }

    private static InstalledDocumentProcessingCapabilities capabilities(
            DocumentParserRegistry registry,
            DoclingParserProperties doclingProperties
    ) {
        var beanFactory = new StaticListableBeanFactory();
        return new InstalledDocumentProcessingCapabilities(
                registry,
                new IngestionProperties(
                        "UTF8_BYTE_BUDGET",
                        128,
                        512,
                        1_024,
                        32,
                        List.of("https")
                ),
                new KnowledgeChunkerFactory(List.of(), List.of()),
                beanFactory.getBeanProvider(EmbeddingSpec.class),
                doclingProperties
        );
    }

    private static DoclingParserProperties doclingProperties(
            boolean enabled,
            boolean docxEnabled
    ) {
        return new DoclingParserProperties(
                enabled,
                docxEnabled,
                URI.create("http://localhost:5001"),
                "",
                enabled ? "docling-v1" : "",
                enabled ? "DoclingDocument@1.10.0" : "",
                Duration.ofSeconds(10),
                Duration.ofMinutes(2),
                Duration.ofSeconds(130),
                2
        );
    }

    private static SpaceDocumentProcessingConfigStore.ChunkerConfiguration chunker(
            String id
    ) {
        return new SpaceDocumentProcessingConfigStore.ChunkerConfiguration(
                id,
                "UTF8_BYTE_BUDGET",
                128,
                512,
                1_024,
                32,
                "{}"
        );
    }

    private static DocumentParser parser(String id) {
        DocumentParser parser = mock(DocumentParser.class);
        when(parser.id()).thenReturn(id);
        when(parser.version()).thenReturn("v1");
        when(parser.canonicalMediaType()).thenReturn("text/plain");
        when(parser.supportedMediaTypes()).thenReturn(Set.of("text/plain"));
        when(parser.supportedExtensions()).thenReturn(Set.of(".txt"));
        when(parser.outputCapabilities()).thenReturn(Set.of(
                ParserOutputCapability.STANDARD_ELEMENTS
        ));
        return parser;
    }

    /** 仅用于证明控制面无需增加策略分支即可展示和校验第三方 Adapter。 */
    private static final class FakeStructureProvider implements KnowledgeChunkerProvider {

        @Override
        public String id() {
            return "FAKE_STRUCTURE";
        }

        @Override
        public String version() {
            return "fake-structure-provider-v1";
        }

        @Override
        public String unavailableReason() {
            return "";
        }

        @Override
        public Set<ParserOutputCapability> requiredParserCapabilities() {
            return Set.of(
                    ParserOutputCapability.STANDARD_ELEMENTS,
                    ParserOutputCapability.HIERARCHY
            );
        }

        @Override
        public String defaultConfigurationJson() {
            return "{}";
        }

        @Override
        public ChunkBoundaryStrategy create(
                SpaceDocumentProcessingConfigStore.ChunkerConfiguration configuration
        ) {
            return new ChunkBoundaryStrategy() {
                @Override
                public String contract() {
                    return "fake-structure-boundary-v1";
                }

                @Override
                public ChunkBoundaryAdvice advise(List<ElementSlice> slices) {
                    return ChunkBoundaryAdvice.none();
                }
            };
        }
    }

    /** 只用于验证模型绑定门禁，不承担真实 Token 计数。 */
    private static final class FakeExactTokenCounter implements TokenCounter {
        private final String modelProfileId;

        private FakeExactTokenCounter(String modelProfileId) {
            this.modelProfileId = modelProfileId;
        }

        @Override
        public String id() {
            return "FAKE_EXACT";
        }

        @Override
        public String version() {
            return "fake-exact-v1";
        }

        @Override
        public String description() {
            return "测试用精确 Tokenizer";
        }

        @Override
        public boolean exactModelTokens() {
            return true;
        }

        @Override
        public String modelProfileId() {
            return modelProfileId;
        }

        @Override
        public int count(String text) {
            return text.codePointCount(0, text.length());
        }

        @Override
        public int maximumPrefixEnd(
                String text,
                int startOffset,
                int endOffset,
                int maximumTokens
        ) {
            int offset = startOffset;
            int remaining = maximumTokens;
            while (offset < endOffset && remaining > 0) {
                offset += Character.charCount(text.codePointAt(offset));
                remaining--;
            }
            return Math.min(offset, endOffset);
        }
    }
}
