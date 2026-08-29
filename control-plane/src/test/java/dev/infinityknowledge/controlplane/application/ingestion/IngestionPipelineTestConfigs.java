package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import dev.infinityknowledge.ingestion.cleaning.DeterministicDocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningPolicy;
import dev.infinityknowledge.ingestion.config.DocumentProcessingContractFactory;
import dev.infinityknowledge.ingestion.parser.DocumentParserRegistry;
import dev.infinityknowledge.ingestion.config.SpaceDocumentProcessingConfigResolver;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;

import java.time.Instant;
import java.util.List;

/** 供摄取用例测试复用的确定性空间配置。 */
final class IngestionPipelineTestConfigs {

    private IngestionPipelineTestConfigs() {
    }

    /** 返回不访问数据库的确定性结构文档处理配置解析器。 */
    static SpaceDocumentProcessingConfigResolver structural(
            DocumentParserRegistry parsers,
            int targetTokens,
            int maximumTokens
    ) {
        return structural(parsers, targetTokens, maximumTokens, 1L);
    }

    /** 返回带指定乐观版本的确定性结构文档处理配置解析器。 */
    static SpaceDocumentProcessingConfigResolver structural(
            DocumentParserRegistry parsers,
            int targetTokens,
            int maximumTokens,
            long version
    ) {
        return structural(
                parsers,
                targetTokens,
                maximumTokens,
                CleaningConfiguration.defaults(),
                new DeterministicDocumentCleaningPolicy(),
                version
        );
    }

    /** 返回带指定清洗配置和乐观版本的确定性结构解析器。 */
    static SpaceDocumentProcessingConfigResolver structural(
            DocumentParserRegistry parsers,
            int targetTokens,
            int maximumTokens,
            CleaningConfiguration cleaning,
            DocumentCleaningPolicy cleaner,
            long version
    ) {
        var chunkers = new KnowledgeChunkerFactory(List.of(), List.of());
        var contracts = new DocumentProcessingContractFactory(
                parsers,
                chunkers,
                cleaner
        );
        return (tenantId, spaceId) -> {
            var request = new SpaceDocumentProcessingConfig(
                    tenantId,
                    spaceId,
                    parsers.defaultParserSelections(),
                    cleaning,
                    new ChunkerConfiguration(
                            "STRUCTURAL",
                            "UTF8_BYTE_BUDGET",
                            Math.min(128, targetTokens),
                            targetTokens,
                            maximumTokens,
                            Math.min(32, Math.min(128, targetTokens) - 1),
                            "{}"
                    ),
                    0L,
                    Instant.EPOCH
            );
            return new SpaceDocumentProcessingConfig(
                    request.tenantId(),
                    request.spaceId(),
                    request.parserSelections(),
                    request.cleaning(),
                    request.chunker(),
                    contracts.create(request),
                    version,
                    null,
                    Instant.EPOCH
            );
        };
    }
}
