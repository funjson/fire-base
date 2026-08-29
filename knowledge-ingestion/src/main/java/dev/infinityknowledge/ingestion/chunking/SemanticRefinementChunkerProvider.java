package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.ingestion.chunking.semantic.EmbeddingSemanticBoundaryStrategy;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingBudget;
import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingException;
import dev.infinityknowledge.ingestion.parser.ParserOutputCapability;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** 内置双向 Embedding 语义边界 Provider。 */
final class SemanticRefinementChunkerProvider implements KnowledgeChunkerProvider {
    private static final String DISABLED = "SEMANTIC_REFINEMENT_DISABLED";
    private static final int DEFAULT_CONTEXT_SLICES = 1;
    private static final double DEFAULT_MERGE_SIMILARITY = 0.85D;
    private static final double DEFAULT_SPLIT_SIMILARITY = 0.6D;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingSpec embeddingSpec;
    private final SemanticChunkingBudget budget;
    private final ExecutorService executor;

    /** 创建只用于能力目录解释的不可用 Provider。 */
    SemanticRefinementChunkerProvider() {
        embeddingProvider = null;
        embeddingSpec = null;
        budget = null;
        executor = null;
    }

    /** 创建绑定部署级 Embedding Profile、预算和有界执行器的可用 Provider。 */
    SemanticRefinementChunkerProvider(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            SemanticChunkingBudget budget,
            ExecutorService executor
    ) {
        this.embeddingProvider = Objects.requireNonNull(
                embeddingProvider,
                "embeddingProvider must not be null"
        );
        this.embeddingSpec = Objects.requireNonNull(
                embeddingSpec,
                "embeddingSpec must not be null"
        );
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public String id() {
        return KnowledgeChunkerFactory.SEMANTIC_REFINEMENT;
    }

    @Override
    public String version() {
        return EmbeddingSemanticBoundaryStrategy.VERSION;
    }

    @Override
    public String unavailableReason() {
        return embeddingProvider == null ? DISABLED : "";
    }

    @Override
    public Set<ParserOutputCapability> requiredParserCapabilities() {
        return Set.of(ParserOutputCapability.STANDARD_ELEMENTS);
    }

    @Override
    public String defaultConfigurationJson() {
        String profile = embeddingSpec == null
                ? "UNAVAILABLE"
                : EmbeddingSemanticBoundaryStrategy.embeddingProfileId(embeddingSpec);
        SemanticProviderConfiguration configuration = new SemanticProviderConfiguration(
                DEFAULT_CONTEXT_SLICES,
                profile,
                DEFAULT_MERGE_SIMILARITY,
                DEFAULT_SPLIT_SIMILARITY
        );
        try {
            return JSON_MAPPER.writeValueAsString(configuration);
        } catch (JacksonException serializationFailure) {
            throw new IllegalStateException(
                    "failed to serialize semantic provider default configuration",
                    serializationFailure
            );
        }
    }

    @Override
    public ChunkBoundaryStrategy create(ChunkerConfiguration configuration) {
        if (embeddingProvider == null) {
            throw new SemanticChunkingException(
                    KnowledgeChunkerFactory.SEMANTIC_REFINEMENT_UNAVAILABLE,
                    "semantic refinement is unavailable in this deployment"
            );
        }
        SemanticProviderConfiguration providerConfiguration = parseCanonical(
                configuration.providerConfigurationJson()
        );
        String installedProfile = EmbeddingSemanticBoundaryStrategy.embeddingProfileId(
                embeddingSpec
        );
        if (!installedProfile.equals(providerConfiguration.embeddingProfileId())) {
            throw new IllegalArgumentException(
                    "configured embeddingProfileId does not match the installed profile"
            );
        }
        return new EmbeddingSemanticBoundaryStrategy(
                embeddingProvider,
                embeddingSpec,
                providerConfiguration.splitSimilarityThreshold(),
                providerConfiguration.mergeSimilarityThreshold(),
                providerConfiguration.contextSlices(),
                budget,
                executor
        );
    }

    /** 严格拒绝未知、缺失、非 canonical 或类型错误的 Provider 配置。 */
    private static SemanticProviderConfiguration parseCanonical(String json) {
        try {
            JsonNode root = JSON_MAPPER.readTree(json);
            if (!root.isObject() || root.size() != 4
                    || !root.has("contextSlices")
                    || !root.has("embeddingProfileId")
                    || !root.has("mergeSimilarityThreshold")
                    || !root.has("splitSimilarityThreshold")) {
                throw invalidConfiguration();
            }
            JsonNode context = root.path("contextSlices");
            JsonNode profile = root.path("embeddingProfileId");
            JsonNode merge = root.path("mergeSimilarityThreshold");
            JsonNode split = root.path("splitSimilarityThreshold");
            if (!context.isIntegralNumber() || !profile.isString()
                    || !merge.isNumber() || !split.isNumber()) {
                throw invalidConfiguration();
            }
            SemanticProviderConfiguration configuration = new SemanticProviderConfiguration(
                    context.asInt(),
                    profile.asString(),
                    merge.asDouble(),
                    split.asDouble()
            );
            String canonical = JSON_MAPPER.writeValueAsString(configuration);
            if (!canonical.equals(json)) {
                throw new IllegalArgumentException(
                        "SEMANTIC_REFINEMENT provider configuration must be canonical JSON"
                );
            }
            return configuration;
        } catch (JacksonException parseFailure) {
            throw new IllegalArgumentException(
                    "SEMANTIC_REFINEMENT provider configuration must be valid JSON",
                    parseFailure
            );
        }
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException(
                "SEMANTIC_REFINEMENT provider configuration requires exactly "
                        + "contextSlices, embeddingProfileId, mergeSimilarityThreshold and "
                        + "splitSimilarityThreshold"
        );
    }

    /** Provider canonical JSON 对应的强类型配置。 */
    private record SemanticProviderConfiguration(
            int contextSlices,
            String embeddingProfileId,
            double mergeSimilarityThreshold,
            double splitSimilarityThreshold
    ) {
        private SemanticProviderConfiguration {
            embeddingProfileId = Objects.requireNonNull(
                    embeddingProfileId,
                    "embeddingProfileId must not be null"
            );
            if (embeddingProfileId.isBlank() || embeddingProfileId.length() > 256) {
                throw new IllegalArgumentException(
                        "embeddingProfileId must contain 1 to 256 visible characters"
                );
            }
            if (contextSlices < 0 || contextSlices > 2) {
                throw new IllegalArgumentException("contextSlices must be between 0 and 2");
            }
            if (!Double.isFinite(splitSimilarityThreshold)
                    || !Double.isFinite(mergeSimilarityThreshold)
                    || splitSimilarityThreshold < -1.0D
                    || mergeSimilarityThreshold > 1.0D
                    || splitSimilarityThreshold >= mergeSimilarityThreshold) {
                throw new IllegalArgumentException(
                        "similarity thresholds must satisfy -1 <= split < merge <= 1"
                );
            }
        }
    }
}
