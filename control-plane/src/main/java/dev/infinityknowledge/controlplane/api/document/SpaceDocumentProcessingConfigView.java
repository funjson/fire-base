package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.controlplane.application.ingestion.DocumentProcessingCapabilities;
import dev.infinityknowledge.controlplane.application.ingestion.ChunkerProviderConfigurationJson;
import dev.infinityknowledge.controlplane.application.ingestion.SpaceDocumentProcessingConfigService;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 空间 Parser、内容清洗、Chunker 当前配置和可选部署能力。 */
public record SpaceDocumentProcessingConfigView(
        String spaceId,
        long version,
        Instant updatedAt,
        String updatedBy,
        DocumentProcessingContractView processingContract,
        boolean runtimeContractMatched,
        List<ParserSelection> parserSelections,
        CleaningConfiguration cleaning,
        ChunkerConfiguration chunker,
        List<AvailableParser> availableParsers,
        List<AvailableChunker> availableChunkers,
        List<AvailableTokenizer> availableTokenizers,
        List<AvailableEmbeddingProfile> availableEmbeddingProfiles
) {

    /** 将不包含 HTTP 类型的应用结果转换为稳定响应。 */
    public static SpaceDocumentProcessingConfigView from(
            SpaceDocumentProcessingConfigService.ConfigDetails details
    ) {
        SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig config = details.config();
        return new SpaceDocumentProcessingConfigView(
                config.spaceId().value(),
                config.version(),
                config.updatedAt(),
                config.updatedBy().value(),
                DocumentProcessingContractView.from(
                        config.processingContract()
                ),
                details.runtimeContractMatched(),
                config.parserSelections().entrySet().stream()
                        .map(entry -> new ParserSelection(entry.getKey(), entry.getValue()))
                        .toList(),
                cleaning(config.cleaning()),
                chunker(config.chunker()),
                availableParsers(details.capabilities()),
                availableChunkers(details.capabilities()),
                availableTokenizers(details.capabilities()),
                availableEmbeddingProfiles(details.capabilities())
        );
    }

    static CleaningConfiguration cleaning(
            SpaceDocumentProcessingConfigStore.CleaningConfiguration value
    ) {
        return new CleaningConfiguration(
                value.header().name(),
                value.footer().name(),
                value.pageNumber().name(),
                value.watermark().name(),
                value.frontMatter().name()
        );
    }

    static ChunkerConfiguration chunker(
            SpaceDocumentProcessingConfigStore.ChunkerConfiguration value
    ) {
        return new ChunkerConfiguration(
                value.providerId(),
                value.tokenizerId(),
                value.minimumTokens(),
                value.targetTokens(),
                value.maximumTokens(),
                value.overlapTokens(),
                ChunkerProviderConfigurationJson.decode(
                        value.providerConfigurationJson()
                )
        );
    }

    static List<AvailableParser> availableParsers(
            DocumentProcessingCapabilities.Snapshot snapshot
    ) {
        return snapshot.parsers().stream()
                .map(parser -> new AvailableParser(
                        parser.id(),
                        parser.version(),
                        parser.canonicalMediaType(),
                        parser.defaultSelection(),
                        parser.available(),
                        parser.unavailableReason().isEmpty()
                                ? null : parser.unavailableReason(),
                        parser.supportedMediaTypes(),
                        parser.supportedExtensions(),
                        parser.outputCapabilities()
                ))
                .sorted(Comparator.comparing(AvailableParser::id))
                .toList();
    }

    static List<AvailableChunker> availableChunkers(
            DocumentProcessingCapabilities.Snapshot snapshot
    ) {
        return snapshot.chunkers().stream()
                .map(value -> new AvailableChunker(
                        value.id(),
                        value.version(),
                        value.available(),
                        value.unavailableReason().isEmpty()
                                ? null : value.unavailableReason(),
                        value.requiredParserCapabilities(),
                        ChunkerProviderConfigurationJson.decode(
                                value.defaultProviderConfigurationJson()
                        )
                ))
                .toList();
    }

    static List<AvailableTokenizer> availableTokenizers(
            DocumentProcessingCapabilities.Snapshot snapshot
    ) {
        return snapshot.tokenizers().stream()
                .map(value -> new AvailableTokenizer(
                        value.id(),
                        value.version(),
                        value.description(),
                        value.exactModelTokens(),
                        value.modelProfileId().isEmpty()
                                ? null : value.modelProfileId(),
                        value.available(),
                        value.unavailableReason().isEmpty()
                                ? null : value.unavailableReason()
                ))
                .toList();
    }

    static List<AvailableEmbeddingProfile> availableEmbeddingProfiles(
            DocumentProcessingCapabilities.Snapshot snapshot
    ) {
        return snapshot.embeddingProfiles().stream()
                .map(value -> new AvailableEmbeddingProfile(
                        value.id(),
                        value.description()
                ))
                .toList();
    }

    /** 当前选择的一种格式与 Parser。 */
    public record ParserSelection(String mediaType, String parserId) {
    }

    /** 当前空间对常见文档家具和前置元数据的治理方式。 */
    public record CleaningConfiguration(
            String header,
            String footer,
            String pageNumber,
            String watermark,
            String frontMatter
    ) {
    }

    /** 当前空间的 Chunker Provider、统一 Token 预算和专属配置。 */
    public record ChunkerConfiguration(
            String providerId,
            String tokenizerId,
            int minimumTokens,
            int targetTokens,
            int maximumTokens,
            int overlapTokens,
            Map<String, Object> providerConfig
    ) {
    }

    /** 一个可发现 Parser 的标识、版本、格式范围与当前部署可用状态。 */
    public record AvailableParser(
            String id,
            String version,
            String canonicalMediaType,
            boolean defaultSelection,
            boolean available,
            String unavailableReason,
            List<String> mediaTypes,
            List<String> extensions,
            List<String> outputCapabilities
    ) {
    }

    /** Chunker Provider 是否可用、实现版本、Parser 前置条件和默认专属配置。 */
    public record AvailableChunker(
            String id,
            String version,
            boolean available,
            String unavailableReason,
            List<String> requiredParserCapabilities,
            Map<String, Object> defaultProviderConfig
    ) {
    }

    /** 页面可选择的 Token Counter；明确区分预算估算与精确模型 Token。 */
    public record AvailableTokenizer(
            String id,
            String version,
            String description,
            boolean exactModelTokens,
            String modelProfileId,
            boolean available,
            String unavailableReason
    ) {
    }

    /** 页面可选择的 Embedding 契约，不返回凭据和 Endpoint。 */
    public record AvailableEmbeddingProfile(String id, String description) {
    }
}
