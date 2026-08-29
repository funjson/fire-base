package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.ingestion.chunking.semantic.SemanticChunkingBudget;
import dev.infinityknowledge.ingestion.parser.ParserOutputCapability;
import dev.infinityknowledge.spi.embedding.EmbeddingProvider;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 根据 Space 配置创建平台托管 Chunker。
 *
 * <p>Provider Registry 只选择边界策略；Factory 始终把它包进平台统一的
 * ElementSlice、结构规划和 Chunk 物化链路，第三方实现无法绕过引用约束。</p>
 */
public final class KnowledgeChunkerFactory {
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    /** 内置确定性结构 Provider 标识。 */
    public static final String STRUCTURAL = "STRUCTURAL";
    /** 内置双向 Embedding 语义增强 Provider 标识。 */
    public static final String SEMANTIC_REFINEMENT = "SEMANTIC_REFINEMENT";
    /** 当前部署无法执行语义增强。 */
    public static final String SEMANTIC_REFINEMENT_UNAVAILABLE =
            "SEMANTIC_REFINEMENT_UNAVAILABLE";

    private final Map<String, KnowledgeChunkerProvider> providersById;
    private final List<KnowledgeChunkerProvider> providers;
    private final Map<String, TokenCounter> tokenCountersById;

    /** 在未启用语义模型时注册外部边界 Adapter 与 Token 计数 Adapter。 */
    public KnowledgeChunkerFactory(
            Collection<KnowledgeChunkerProvider> additionalProviders,
            Collection<TokenCounter> additionalTokenCounters
    ) {
        this(
                additionalProviders,
                additionalTokenCounters,
                new SemanticRefinementChunkerProvider()
        );
    }

    /** 创建同时安装外部边界 Adapter、TokenCounter 和语义运行时的工厂。 */
    public KnowledgeChunkerFactory(
            EmbeddingProvider embeddingProvider,
            EmbeddingSpec embeddingSpec,
            SemanticChunkingBudget semanticBudget,
            ExecutorService semanticExecutor,
            Collection<KnowledgeChunkerProvider> additionalProviders,
            Collection<TokenCounter> additionalTokenCounters
    ) {
        this(
                additionalProviders,
                additionalTokenCounters,
                new SemanticRefinementChunkerProvider(
                        embeddingProvider,
                        embeddingSpec,
                        semanticBudget,
                        semanticExecutor
                )
        );
    }

    private KnowledgeChunkerFactory(
            Collection<KnowledgeChunkerProvider> additionalProviders,
            Collection<TokenCounter> additionalTokenCounters,
            KnowledgeChunkerProvider semanticProvider
    ) {
        providersById = registerProviders(additionalProviders, semanticProvider);
        providers = providersById.values().stream()
                .sorted(Comparator.comparing(KnowledgeChunkerProvider::id))
                .toList();
        tokenCountersById = registerTokenCounters(additionalTokenCounters);
    }

    /** 按已校验配置创建平台托管 Chunker。 */
    public KnowledgeChunker create(ChunkerConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        KnowledgeChunkerProvider provider = requireAvailableProvider(
                configuration.providerId()
        );
        TokenCounter tokenCounter = requireTokenCounter(configuration.tokenizerId());
        ChunkBoundaryStrategy strategy = Objects.requireNonNull(
                provider.create(configuration),
                "knowledge chunker provider returned null boundary strategy"
        );
        ChunkSizing sizing = new ChunkSizing(
                tokenCounter,
                configuration.minimumTokens(),
                configuration.targetTokens(),
                configuration.maximumTokens(),
                configuration.overlapTokens()
        );
        return new ManagedKnowledgeChunker(
                provider.id(),
                provider.version(),
                strategy,
                sizing
        );
    }

    /** 返回完整处理契约，不执行切分或模型调用。 */
    public String contract(ChunkerConfiguration configuration) {
        return create(configuration).contract();
    }

    /** 返回稳定排序且不可变的已安装 Provider 目录。 */
    public List<KnowledgeChunkerProvider> providers() {
        return providers;
    }

    /** 返回稳定排序且不可变的 TokenCounter 能力目录。 */
    public List<TokenCounter> tokenCounters() {
        return tokenCountersById.values().stream()
                .sorted(Comparator.comparing(TokenCounter::id))
                .toList();
    }

    /**
     * 返回创建 Chunker 与验收重算共同使用的同一个 TokenCounter 实例。
     *
     * <p>调用方不得按标识重新构造一个近似实现，否则实际切分合同与验收计数可能
     * 漂移。测试广场应在配置快照确定后调用本方法，再交给 ObservationFactory。</p>
     */
    public TokenCounter requireTokenCounter(String tokenizerId) {
        TokenCounter tokenCounter = tokenCountersById.get(tokenizerId);
        if (tokenCounter == null) {
            throw new IllegalArgumentException("selected TokenCounter is not installed");
        }
        return tokenCounter;
    }

    /** 判断 Provider 是否已安装且当前可执行。 */
    public boolean supports(String providerId) {
        KnowledgeChunkerProvider provider = providersById.get(providerId);
        return provider != null && provider.unavailableReason().isEmpty();
    }

    private KnowledgeChunkerProvider requireAvailableProvider(String providerId) {
        KnowledgeChunkerProvider provider = providersById.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "selected knowledge chunker provider is not installed"
            );
        }
        if (!provider.unavailableReason().isEmpty()) {
            throw new IllegalStateException(
                    "selected knowledge chunker provider is unavailable: "
                            + provider.unavailableReason()
            );
        }
        return provider;
    }

    private static Map<String, KnowledgeChunkerProvider> registerProviders(
            Collection<KnowledgeChunkerProvider> additionalProviders,
            KnowledgeChunkerProvider semanticProvider
    ) {
        Objects.requireNonNull(additionalProviders, "additionalProviders must not be null");
        List<KnowledgeChunkerProvider> installed = new ArrayList<>();
        installed.add(new StructuralKnowledgeChunkerProvider());
        installed.add(Objects.requireNonNull(semanticProvider, "semanticProvider must not be null"));
        installed.addAll(additionalProviders);
        Map<String, KnowledgeChunkerProvider> registered = new LinkedHashMap<>();
        for (KnowledgeChunkerProvider provider : installed) {
            validateProvider(provider);
            if (registered.putIfAbsent(provider.id(), provider) != null) {
                throw new IllegalArgumentException(
                        "duplicate knowledge chunker provider id: " + provider.id()
                );
            }
        }
        return Map.copyOf(registered);
    }

    private static Map<String, TokenCounter> registerTokenCounters(
            Collection<TokenCounter> additionalTokenCounters
    ) {
        Objects.requireNonNull(
                additionalTokenCounters,
                "additionalTokenCounters must not be null"
        );
        List<TokenCounter> installed = new ArrayList<>();
        installed.add(new Utf8ByteBudgetTokenCounter());
        installed.addAll(additionalTokenCounters);
        Map<String, TokenCounter> registered = new LinkedHashMap<>();
        for (TokenCounter counter : installed) {
            validateTokenCounter(counter);
            if (registered.putIfAbsent(counter.id(), counter) != null) {
                throw new IllegalArgumentException("duplicate token counter id: " + counter.id());
            }
        }
        return Map.copyOf(registered);
    }

    private static void validateTokenCounter(TokenCounter counter) {
        Objects.requireNonNull(counter, "token counter must not be null");
        if (!counter.id().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(
                    "token counter id must match [A-Za-z0-9][A-Za-z0-9._:-]{0,127}"
            );
        }
        if (counter.version() == null || counter.version().isBlank()) {
            throw new IllegalArgumentException("token counter version must not be blank");
        }
        if (counter.description() == null || counter.description().isBlank()) {
            throw new IllegalArgumentException("token counter description must not be blank");
        }
        if (counter.contract() == null || counter.contract().isBlank()) {
            throw new IllegalArgumentException("token counter contract must not be blank");
        }
    }

    private static void validateProvider(KnowledgeChunkerProvider provider) {
        Objects.requireNonNull(provider, "knowledge chunker provider must not be null");
        String id = Objects.requireNonNull(provider.id(), "provider id must not be null");
        if (!id.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException(
                    "knowledge chunker provider id must match [A-Z][A-Z0-9_]{0,63}"
            );
        }
        String version = Objects.requireNonNull(
                provider.version(),
                "provider version must not be null"
        );
        if (version.isBlank() || version.length() > 128 || !version.equals(version.strip())) {
            throw new IllegalArgumentException(
                    "knowledge chunker provider version must contain 1 to 128 unpadded characters"
            );
        }
        String unavailableReason = Objects.requireNonNull(
                provider.unavailableReason(),
                "provider unavailableReason must not be null"
        );
        if (!unavailableReason.isEmpty()
                && !unavailableReason.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException(
                    "provider unavailableReason must be an empty string or stable reason code"
            );
        }
        Set<ParserOutputCapability> requirements = Set.copyOf(Objects.requireNonNull(
                provider.requiredParserCapabilities(),
                "provider requiredParserCapabilities must not be null"
        ));
        if (!requirements.contains(ParserOutputCapability.STANDARD_ELEMENTS)) {
            throw new IllegalArgumentException(
                    "knowledge chunker provider must require STANDARD_ELEMENTS"
            );
        }
        validateDefaultConfiguration(provider.defaultConfigurationJson());
    }

    /** Registry 只校验通用 JSON Object 形态；Provider 在 create 时校验专属 Schema。 */
    private static void validateDefaultConfiguration(String configurationJson) {
        Objects.requireNonNull(
                configurationJson,
                "provider defaultConfigurationJson must not be null"
        );
        if (configurationJson.isBlank()) {
            throw new IllegalArgumentException(
                    "provider defaultConfigurationJson must not be blank"
            );
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(configurationJson);
            if (!root.isObject()
                    || !JSON_MAPPER.writeValueAsString(root).equals(configurationJson)) {
                throw new IllegalArgumentException(
                        "provider defaultConfigurationJson must be a canonical JSON object"
                );
            }
        } catch (JacksonException parseFailure) {
            throw new IllegalArgumentException(
                    "provider defaultConfigurationJson must be valid JSON",
                    parseFailure
            );
        }
    }
}
