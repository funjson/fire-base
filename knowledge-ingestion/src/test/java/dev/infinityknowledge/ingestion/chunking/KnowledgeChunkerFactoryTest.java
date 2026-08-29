package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingBudget;
import dev.infinityknowledge.ingestion.parser.ParserOutputCapability;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeChunkerFactoryTest {
    private static final String PROFILE = "embedding-provider/embedding-model@8";
    private ExecutorService executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void createsManagedStructuralChunkerAndExposesTokenizerCapabilities() {
        KnowledgeChunkerFactory factory = new KnowledgeChunkerFactory(List.of(), List.of());

        KnowledgeChunker chunker = factory.create(configuration(
                KnowledgeChunkerFactory.STRUCTURAL,
                "{}"
        ));

        assertThat(chunker.contract())
                .contains("managed-chunker-v2")
                .contains("provider=STRUCTURAL")
                .contains("strategy=structural-boundary-v1")
                .contains("slicer=element-slicer-v2")
                .contains("planner=structural-plan-v2")
                .contains("tokenizer=UTF8_BYTE_BUDGET");
        assertThat(factory.providers())
                .filteredOn(provider -> provider.unavailableReason().isEmpty())
                .extracting(KnowledgeChunkerProvider::id)
                .containsExactly(KnowledgeChunkerFactory.STRUCTURAL);
        assertThat(factory.providers())
                .filteredOn(provider -> provider.id().equals(
                        KnowledgeChunkerFactory.SEMANTIC_REFINEMENT
                ))
                .singleElement()
                .extracting(KnowledgeChunkerProvider::defaultConfigurationJson)
                .asString()
                .contains("\"embeddingProfileId\":\"UNAVAILABLE\"");
        assertThat(factory.tokenCounters()).singleElement().satisfies(counter -> {
            assertThat(counter.id()).isEqualTo(Utf8ByteBudgetTokenCounter.ID);
            assertThat(counter.version()).isEqualTo(Utf8ByteBudgetTokenCounter.VERSION);
            assertThat(counter.exactModelTokens()).isFalse();
            assertThat(counter.description()).contains("不等同于模型精确 Token 数");
            assertThat(factory.requireTokenCounter(counter.id())).isSameAs(counter);
        });
        assertThatThrownBy(() -> factory.requireTokenCounter("NOT_INSTALLED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not installed");
    }

    @Test
    void rejectsSemanticRefinementWhenDeploymentHasNoEmbeddingRuntime() {
        KnowledgeChunkerFactory factory = new KnowledgeChunkerFactory(List.of(), List.of());

        assertThatThrownBy(() -> factory.create(configuration(
                KnowledgeChunkerFactory.SEMANTIC_REFINEMENT,
                semanticJson(PROFILE)
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEMANTIC_REFINEMENT_DISABLED");
    }

    @Test
    void semanticContractContainsAbsoluteThresholdsProfileAndBudgets() {
        KnowledgeChunkerFactory factory = semanticFactory();

        String contract = factory.contract(configuration(
                KnowledgeChunkerFactory.SEMANTIC_REFINEMENT,
                semanticJson(PROFILE)
        ));

        assertThat(contract)
                .contains("provider=SEMANTIC_REFINEMENT")
                .contains("embedding-semantic-boundary-v3")
                .contains("profile=" + PROFILE)
                .contains("splitSimilarity=0.6")
                .contains("mergeSimilarity=0.85")
                .contains("contextSlices=1")
                .contains("timeoutMs=12000")
                .doesNotContain("percentile");
        assertThat(factory.providers())
                .filteredOn(provider -> provider.id().equals(
                        KnowledgeChunkerFactory.SEMANTIC_REFINEMENT
                ))
                .singleElement()
                .extracting(KnowledgeChunkerProvider::defaultConfigurationJson)
                .isEqualTo(semanticJson(PROFILE));
    }

    @Test
    void acceptsHierarchicalExternalTokenizerIdsAllowedByPersistedConfiguration() {
        TokenCounter external = new TokenCounter() {
            @Override
            public String id() {
                return "openai.cl100k_base:v1";
            }

            @Override
            public String version() {
                return "adapter-v1";
            }

            @Override
            public String description() {
                return "测试用精确 Tokenizer Adapter";
            }

            @Override
            public boolean exactModelTokens() {
                return true;
            }

            @Override
            public int count(String text) {
                return text.length();
            }

            @Override
            public int maximumPrefixEnd(
                    String text,
                    int startOffset,
                    int endOffset,
                    int maximumTokens
            ) {
                return Math.min(endOffset, startOffset + maximumTokens);
            }
        };

        KnowledgeChunkerFactory factory = new KnowledgeChunkerFactory(
                List.of(), List.of(external)
        );

        assertThat(factory.tokenCounters()).extracting(TokenCounter::id)
                .contains("openai.cl100k_base:v1");
    }

    @Test
    void strictlyRejectsUnknownMissingNonCanonicalAndWrongProfileConfiguration() {
        KnowledgeChunkerFactory factory = semanticFactory();

        assertThatThrownBy(() -> factory.create(configuration(
                KnowledgeChunkerFactory.SEMANTIC_REFINEMENT,
                "{\"contextSlices\":1}"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires exactly");
        assertThatThrownBy(() -> factory.create(configuration(
                KnowledgeChunkerFactory.SEMANTIC_REFINEMENT,
                "{\"contextSlices\":1,\"embeddingProfileId\":\"" + PROFILE
                        + "\",\"mergeSimilarityThreshold\":0.85,"
                        + "\"splitSimilarityThreshold\":0.6,\"unknown\":true}"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires exactly");
        assertThatThrownBy(() -> factory.create(configuration(
                KnowledgeChunkerFactory.SEMANTIC_REFINEMENT,
                "{ \"contextSlices\": 1, \"embeddingProfileId\": \"" + PROFILE
                        + "\", \"mergeSimilarityThreshold\": 0.85, "
                        + "\"splitSimilarityThreshold\": 0.6 }"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical JSON");
        assertThatThrownBy(() -> factory.create(configuration(
                KnowledgeChunkerFactory.SEMANTIC_REFINEMENT,
                semanticJson("other/model@8")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("installed profile");
    }

    @Test
    void thirdPartyProviderCanOnlyReturnBoundaryAdviceAndUsesPlatformAssembler() {
        KnowledgeChunkerFactory factory = new KnowledgeChunkerFactory(
                List.of(new FakeBoundaryProvider()),
                List.of()
        );

        String contract = factory.contract(configuration("FAKE_BOUNDARY", "{}"));

        assertThat(contract)
                .contains("provider=FAKE_BOUNDARY")
                .contains("strategy=fake-boundary-strategy-v1")
                .contains("assembler=chunk-assembler-v2");
        assertThat(factory.providers()).extracting(KnowledgeChunkerProvider::id)
                .containsExactly("FAKE_BOUNDARY", "SEMANTIC_REFINEMENT", "STRUCTURAL");
    }

    private KnowledgeChunkerFactory semanticFactory() {
        executor = Executors.newSingleThreadExecutor();
        return new KnowledgeChunkerFactory(
                (texts, spec) -> List.of(),
                new EmbeddingSpec("embedding-provider", "embedding-model", 8),
                SemanticChunkingBudget.defaults(),
                executor,
                List.of(),
                List.of()
        );
    }

    private static ChunkerConfiguration configuration(String providerId, String providerJson) {
        return new ChunkerConfiguration(
                providerId,
                Utf8ByteBudgetTokenCounter.ID,
                64,
                256,
                512,
                32,
                providerJson
        );
    }

    private static String semanticJson(String profileId) {
        return "{\"contextSlices\":1,\"embeddingProfileId\":\"" + profileId
                + "\",\"mergeSimilarityThreshold\":0.85,"
                + "\"splitSimilarityThreshold\":0.6}";
    }

    /** 测试 Adapter 只能建议边界，不能接管最终 Chunk 物化。 */
    private static final class FakeBoundaryProvider implements KnowledgeChunkerProvider {
        @Override
        public String id() {
            return "FAKE_BOUNDARY";
        }

        @Override
        public String version() {
            return "fake-boundary-provider-v1";
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
        public ChunkBoundaryStrategy create(ChunkerConfiguration configuration) {
            if (!"{}".equals(configuration.providerConfigurationJson())) {
                throw new IllegalArgumentException("fake provider requires empty configuration");
            }
            return new ChunkBoundaryStrategy() {
                @Override
                public String contract() {
                    return "fake-boundary-strategy-v1";
                }

                @Override
                public ChunkBoundaryAdvice advise(List<ElementSlice> slices) {
                    return ChunkBoundaryAdvice.none();
                }
            };
        }
    }
}
