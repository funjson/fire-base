package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.controlplane.config.IngestionProperties;
import dev.infinityknowledge.controlplane.config.ingestion.DoclingParserProperties;
import dev.infinityknowledge.controlplane.config.ingestion.HuggingFaceTokenizerProperties;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerProvider;
import dev.infinityknowledge.ingestion.chunking.TokenCounter;
import dev.infinityknowledge.ingestion.chunking.semantic.EmbeddingSemanticBoundaryStrategy;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.parser.docling.DoclingDocumentParser;
import dev.infinityknowledge.spi.embedding.EmbeddingSpec;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore
        .SpaceDocumentProcessingConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 根据 Spring 已安装 Bean 与显式外部 Adapter 目录生成空间文档处理配置选项。 */
@Component
public final class InstalledDocumentProcessingCapabilities
        implements DocumentProcessingCapabilities {

    private final Snapshot snapshot;
    private final DocumentParserRegistry parserRegistry;
    private final KnowledgeChunkerFactory chunkerFactory;
    private final DocumentProcessingContractFactory contractFactory;

    /**
     * 建立部署能力快照；运行期间不会因单个用户请求改变。
     */
    public InstalledDocumentProcessingCapabilities(
            DocumentParserRegistry parserRegistry,
            IngestionProperties ingestionProperties,
            KnowledgeChunkerFactory chunkerFactory,
            ObjectProvider<EmbeddingSpec> embeddingSpecs
    ) {
        this(
                parserRegistry,
                ingestionProperties,
                chunkerFactory,
                embeddingSpecs,
                null,
                null,
                defaultContractFactory(parserRegistry, chunkerFactory)
        );
    }

    /** 供不关心 Tokenizer 部署目录的聚焦测试使用。 */
    public InstalledDocumentProcessingCapabilities(
            DocumentParserRegistry parserRegistry,
            IngestionProperties ingestionProperties,
            KnowledgeChunkerFactory chunkerFactory,
            ObjectProvider<EmbeddingSpec> embeddingSpecs,
            DoclingParserProperties doclingProperties
    ) {
        this(
                parserRegistry,
                ingestionProperties,
                chunkerFactory,
                embeddingSpecs,
                doclingProperties,
                null,
                defaultContractFactory(parserRegistry, chunkerFactory)
        );
    }

    /** 兼容不直接构造合同工厂的聚焦测试。 */
    public InstalledDocumentProcessingCapabilities(
            DocumentParserRegistry parserRegistry,
            IngestionProperties ingestionProperties,
            KnowledgeChunkerFactory chunkerFactory,
            ObjectProvider<EmbeddingSpec> embeddingSpecs,
            DoclingParserProperties doclingProperties,
            HuggingFaceTokenizerProperties huggingFaceProperties
    ) {
        this(
                parserRegistry,
                ingestionProperties,
                chunkerFactory,
                embeddingSpecs,
                doclingProperties,
                huggingFaceProperties,
                defaultContractFactory(parserRegistry, chunkerFactory)
        );
    }

    /** Spring 运行时同时接收外部 Parser 配置，用于展示未启用但可安装的能力。 */
    @Autowired
    public InstalledDocumentProcessingCapabilities(
            DocumentParserRegistry parserRegistry,
            IngestionProperties ingestionProperties,
            KnowledgeChunkerFactory chunkerFactory,
            ObjectProvider<EmbeddingSpec> embeddingSpecs,
            DoclingParserProperties doclingProperties,
            HuggingFaceTokenizerProperties huggingFaceProperties,
            DocumentProcessingContractFactory contractFactory
    ) {
        this.parserRegistry = Objects.requireNonNull(
                parserRegistry,
                "parserRegistry must not be null"
        );
        this.chunkerFactory = Objects.requireNonNull(
                chunkerFactory,
                "chunkerFactory must not be null"
        );
        this.contractFactory = Objects.requireNonNull(
                contractFactory,
                "contractFactory must not be null"
        );
        Objects.requireNonNull(
                ingestionProperties,
                "ingestionProperties must not be null"
        );
        Objects.requireNonNull(embeddingSpecs, "embeddingSpecs must not be null");
        List<ParserCapability> installedParserCapabilities = parserRegistry.capabilities().stream()
                .map(InstalledDocumentProcessingCapabilities::capability)
                .toList();
        List<ParserCapability> parserCapabilities = new ArrayList<>(
                installedParserCapabilities
        );
        appendUnavailableDoclingCapabilities(parserCapabilities, doclingProperties);
        parserCapabilities.sort(Comparator.comparing(ParserCapability::canonicalMediaType)
                .thenComparing(ParserCapability::id));
        parserCapabilities = List.copyOf(parserCapabilities);
        if (parserCapabilities.isEmpty()) {
            throw new IllegalStateException("at least one document parser must be installed");
        }
        Map<String, String> defaults = parserRegistry.defaultParserSelections();
        parserRegistry.selectedParsersContract(defaults);
        EmbeddingSpec embeddingSpec = embeddingSpecs.getIfAvailable();
        String embeddingProfileId = embeddingSpec == null
                ? ""
                : EmbeddingSemanticBoundaryStrategy.embeddingProfileId(embeddingSpec);
        List<EmbeddingProfileCapability> embeddingProfiles = embeddingSpec == null
                ? List.of()
                : List.of(new EmbeddingProfileCapability(
                        embeddingProfileId,
                        "当前部署允许语义细化使用的 Embedding 模型、版本与维度契约"
                ));
        List<TokenizerCapability> tokenizerCapabilities = new ArrayList<>(
                chunkerFactory.tokenCounters().stream()
                        .map(InstalledDocumentProcessingCapabilities::capability)
                        .toList()
        );
        appendUnavailableHuggingFaceCapability(
                tokenizerCapabilities,
                huggingFaceProperties
        );
        tokenizerCapabilities = tokenizerCapabilities.stream()
                .map(tokenizer -> withEmbeddingCompatibility(
                        tokenizer,
                        embeddingProfiles
                ))
                .collect(Collectors.toCollection(ArrayList::new));
        tokenizerCapabilities.sort(Comparator.comparing(TokenizerCapability::id));
        ChunkerConfiguration defaultChunker = new ChunkerConfiguration(
                KnowledgeChunkerFactory.STRUCTURAL,
                ingestionProperties.tokenizerId(),
                ingestionProperties.minimumChunkTokens(),
                ingestionProperties.targetChunkTokens(),
                ingestionProperties.maximumChunkTokens(),
                ingestionProperties.overlapChunkTokens(),
                "{}"
        );
        snapshot = new Snapshot(
                parserCapabilities,
                chunkerFactory.providers().stream()
                        .map(InstalledDocumentProcessingCapabilities::capability)
                        .toList(),
                tokenizerCapabilities,
                embeddingProfiles,
                defaults,
                defaultChunker
        );
        validateForCreation(defaults, defaultChunker);
    }

    @Override
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public void validate(
            Map<String, String> parserSelections,
            ChunkerConfiguration chunker
    ) {
        Objects.requireNonNull(parserSelections, "parserSelections must not be null");
        Objects.requireNonNull(chunker, "chunker must not be null");
        rejectUnavailableParserSelections(parserSelections);
        parserRegistry.selectedParsersContract(parserSelections);
        ChunkerCapability selectedChunker = snapshot.chunkers().stream()
                .filter(capability -> capability.id().equals(chunker.providerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "selected chunker provider is not installed"
                ));
        if (!selectedChunker.available()) {
            throw new IllegalArgumentException(
                    "selected chunker provider is unavailable in this deployment"
            );
        }
        TokenizerCapability selectedTokenizer = snapshot.tokenizers().stream()
                .filter(capability -> capability.id().equals(chunker.tokenizerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "selected Tokenizer is not installed"
                ));
        if (!selectedTokenizer.available()) {
            throw new IllegalArgumentException(
                    "selected Tokenizer is unavailable in this deployment"
            );
        }
        validateParserRequirements(parserSelections, selectedChunker);
        validateTokenizerModelBinding(chunker, selectedTokenizer);
        chunkerFactory.contract(chunker);
    }

    /** 创建时必须把当前部署的全部规范格式显式物化进 Space 快照。 */
    @Override
    public void validateForCreation(
            Map<String, String> parserSelections,
            ChunkerConfiguration chunker
    ) {
        validate(parserSelections, chunker);
        Set<String> installedCanonicalMediaTypes = parserRegistry.capabilities().stream()
                .map(dev.infinityknowledge.ingestion.parser.ParserCapability
                        ::canonicalMediaType)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!parserSelections.keySet().equals(installedCanonicalMediaTypes)) {
            throw new IllegalArgumentException(
                    "new space parser selections must cover every installed canonical media type"
            );
        }
    }

    @Override
    public DocumentProcessingContract processingContract(
            SpaceDocumentProcessingConfig config
    ) {
        Objects.requireNonNull(config, "config must not be null");
        validate(config.parserSelections(), config.chunker());
        return contractFactory.create(config);
    }

    private static DocumentProcessingContractFactory defaultContractFactory(
            DocumentParserRegistry parserRegistry,
            KnowledgeChunkerFactory chunkerFactory
    ) {
        return new DocumentProcessingContractFactory(
                parserRegistry,
                chunkerFactory,
                new DeterministicDocumentCleaningPolicy()
        );
    }

    private static ParserCapability capability(
            dev.infinityknowledge.ingestion.parser.ParserCapability parser
    ) {
        Objects.requireNonNull(parser, "parser must not be null");
        return new ParserCapability(
                parser.parserId(),
                parser.parserVersion(),
                parser.canonicalMediaType(),
                parser.supportedMediaTypes(),
                parser.supportedExtensions(),
                parser.outputCapabilities().stream()
                        .map(Enum::name)
                        .toList(),
                parser.defaultSelection(),
                true,
                ""
        );
    }

    private static void appendUnavailableDoclingCapabilities(
            List<ParserCapability> capabilities,
            DoclingParserProperties properties
    ) {
        if (properties == null) {
            return;
        }
        String pdfReason = properties.enabled()
                ? "DOCLING_PARSER_NOT_INSTALLED"
                : "DOCLING_PARSER_DISABLED";
        appendIfMissing(capabilities, new ParserCapability(
                "docling-serve-pdf",
                "docling-java-" + DoclingDocumentParser.DOCLING_JAVA_VERSION + "-unavailable",
                "application/pdf",
                List.of("application/pdf"),
                List.of(".pdf"),
                List.of("STANDARD_ELEMENTS", "HIERARCHY", "PAGE_NUMBER", "FLAT_TABLE_TEXT"),
                false,
                false,
                pdfReason
        ));
        String docxReason;
        if (!properties.enabled()) {
            docxReason = "DOCLING_PARSER_DISABLED";
        } else if (!properties.docxEnabled()) {
            docxReason = "DOCLING_DOCX_PARSER_DISABLED";
        } else {
            docxReason = "DOCLING_PARSER_NOT_INSTALLED";
        }
        appendIfMissing(capabilities, new ParserCapability(
                "docling-serve-docx",
                "docling-java-" + DoclingDocumentParser.DOCLING_JAVA_VERSION + "-unavailable",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                List.of(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                ),
                List.of(".docx"),
                List.of("STANDARD_ELEMENTS", "HIERARCHY", "FLAT_TABLE_TEXT"),
                false,
                false,
                docxReason
        ));
    }

    private static void appendIfMissing(
            List<ParserCapability> capabilities,
            ParserCapability candidate
    ) {
        if (capabilities.stream().noneMatch(value -> value.id().equals(candidate.id()))) {
            capabilities.add(candidate);
        }
    }

    private void rejectUnavailableParserSelections(Map<String, String> parserSelections) {
        Map<String, ParserCapability> parsersById = snapshot.parsers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        ParserCapability::id,
                        Function.identity()
                ));
        for (String parserId : parserSelections.values()) {
            ParserCapability capability = parsersById.get(parserId);
            if (capability != null && !capability.available()) {
                throw new IllegalArgumentException(
                        "selected parser is unavailable in this deployment"
                );
            }
        }
    }

    private static ChunkerCapability capability(
            KnowledgeChunkerProvider provider
    ) {
        Objects.requireNonNull(provider, "provider must not be null");
        String unavailableReason = provider.unavailableReason();
        return new ChunkerCapability(
                provider.id(),
                provider.version(),
                unavailableReason.isEmpty(),
                unavailableReason,
                provider.requiredParserCapabilities().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList(),
                provider.defaultConfigurationJson()
        );
    }

    private static TokenizerCapability capability(TokenCounter tokenCounter) {
        Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
        return new TokenizerCapability(
                tokenCounter.id(),
                tokenCounter.version(),
                tokenCounter.description(),
                tokenCounter.exactModelTokens(),
                tokenCounter.modelProfileId(),
                true,
                ""
        );
    }

    /**
     * 安装成功不代表当前 Embedding 部署可以安全使用该精确 Tokenizer。
     * 能力目录必须在用户选择前就暴露绑定不一致，不能先标记可用再在创建 Space 时返回 400。
     */
    private static TokenizerCapability withEmbeddingCompatibility(
            TokenizerCapability tokenizer,
            List<EmbeddingProfileCapability> embeddingProfiles
    ) {
        if (!tokenizer.available()
                || !tokenizer.exactModelTokens()
                || embeddingProfiles.isEmpty()
                || embeddingProfiles.stream().anyMatch(
                        profile -> profile.id().equals(tokenizer.modelProfileId())
                )) {
            return tokenizer;
        }
        return new TokenizerCapability(
                tokenizer.id(),
                tokenizer.version(),
                tokenizer.description(),
                true,
                tokenizer.modelProfileId(),
                false,
                "TOKENIZER_EMBEDDING_PROFILE_MISMATCH"
        );
    }

    /** 未配置本地文件时仍把外部 Tokenizer 展示为不可选能力，而不是让它从页面消失。 */
    private static void appendUnavailableHuggingFaceCapability(
            List<TokenizerCapability> capabilities,
            HuggingFaceTokenizerProperties properties
    ) {
        if (properties == null) {
            return;
        }
        String id = properties.id().isBlank() ? "HUGGINGFACE_LOCAL" : properties.id();
        if (capabilities.stream().anyMatch(value -> value.id().equals(id))) {
            return;
        }
        capabilities.add(new TokenizerCapability(
                id,
                "huggingface-tokenizers-unavailable",
                "本地 tokenizer.json 精确模型 Token 计数；启用时必须固定文件、SHA-256 与模型配置",
                true,
                properties.modelProfileId(),
                false,
                properties.enabled()
                        ? "HUGGINGFACE_TOKENIZER_NOT_INSTALLED"
                        : "HUGGINGFACE_TOKENIZER_DISABLED"
        ));
    }

    /** 验证每一种有效格式的 Parser 都满足所选 Chunker 的结构前置条件。 */
    private void validateParserRequirements(
            Map<String, String> parserSelections,
            ChunkerCapability selectedChunker
    ) {
        Map<String, ParserCapability> parsersById = snapshot.parsers().stream()
                .collect(Collectors.toUnmodifiableMap(
                        ParserCapability::id,
                        Function.identity()
                ));
        Set<String> requirements = Set.copyOf(
                selectedChunker.requiredParserCapabilities()
        );
        for (String parserId : parserSelections.values()) {
            ParserCapability parser = parsersById.get(parserId);
            if (parser == null
                    || !Set.copyOf(parser.outputCapabilities()).containsAll(requirements)) {
                throw new IllegalArgumentException(
                        "selected parser does not satisfy chunker provider requirements"
                );
            }
        }
    }

    /**
     * 精确 Tokenizer 只能和运维固定的模型契约配对，防止页面把近似预算误标为模型硬上限。
     */
    private void validateTokenizerModelBinding(
            ChunkerConfiguration chunker,
            TokenizerCapability tokenizer
    ) {
        if (!tokenizer.exactModelTokens()) {
            return;
        }
        String modelProfileId = tokenizer.modelProfileId();
        if (modelProfileId.isBlank()) {
            throw new IllegalArgumentException(
                    "exact Tokenizer must declare a modelProfileId"
            );
        }
        if (!snapshot.embeddingProfiles().isEmpty()
                && snapshot.embeddingProfiles().stream()
                .noneMatch(profile -> profile.id().equals(modelProfileId))) {
            throw new IllegalArgumentException(
                    "selected Tokenizer does not match an installed Embedding profile"
            );
        }
        if (KnowledgeChunkerFactory.SEMANTIC_REFINEMENT.equals(chunker.providerId())) {
            Object semanticProfileId = ChunkerProviderConfigurationJson
                    .decode(chunker.providerConfigurationJson())
                    .get("embeddingProfileId");
            if (!modelProfileId.equals(semanticProfileId)) {
                throw new IllegalArgumentException(
                        "selected Tokenizer does not match semantic embeddingProfileId"
                );
            }
        }
    }

}
