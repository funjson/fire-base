package dev.infinityknowledge.controlplane.api.document;

import dev.infinityknowledge.controlplane.application.ingestion.DocumentProcessingCapabilities;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore;

import java.util.List;

/** 创建 Space 和测试广场共用的部署能力目录与默认配置。 */
public record DocumentProcessingCapabilitiesView(
        DefaultConfig defaultConfig,
        List<SpaceDocumentProcessingConfigView.AvailableParser> availableParsers,
        List<SpaceDocumentProcessingConfigView.AvailableChunker> availableChunkers,
        List<SpaceDocumentProcessingConfigView.AvailableTokenizer> availableTokenizers,
        List<SpaceDocumentProcessingConfigView.AvailableEmbeddingProfile>
                availableEmbeddingProfiles
) {

    /** 将应用能力快照转换为不包含密钥和 Endpoint 的 HTTP 响应。 */
    public static DocumentProcessingCapabilitiesView from(
            DocumentProcessingCapabilities.Snapshot snapshot
    ) {
        var defaults = snapshot.defaultParserSelections().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new SpaceDocumentProcessingConfigView.ParserSelection(
                        entry.getKey(),
                        entry.getValue()
                ))
                .toList();
        return new DocumentProcessingCapabilitiesView(
                new DefaultConfig(
                        defaults,
                        SpaceDocumentProcessingConfigView.cleaning(
                                SpaceDocumentProcessingConfigStore
                                        .CleaningConfiguration.defaults()
                        ),
                        SpaceDocumentProcessingConfigView.chunker(
                                snapshot.defaultChunker()
                        )
                ),
                SpaceDocumentProcessingConfigView.availableParsers(snapshot),
                SpaceDocumentProcessingConfigView.availableChunkers(snapshot),
                SpaceDocumentProcessingConfigView.availableTokenizers(snapshot),
                SpaceDocumentProcessingConfigView.availableEmbeddingProfiles(snapshot)
        );
    }

    /** 创建表单可直接提交的一套默认配置。 */
    public record DefaultConfig(
            List<SpaceDocumentProcessingConfigView.ParserSelection> parserSelections,
            SpaceDocumentProcessingConfigView.CleaningConfiguration cleaning,
            SpaceDocumentProcessingConfigView.ChunkerConfiguration chunker
    ) {
    }
}
