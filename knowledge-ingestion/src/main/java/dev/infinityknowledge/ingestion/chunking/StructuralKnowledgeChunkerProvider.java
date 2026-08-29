package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.ingestion.parser.ParserOutputCapability;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;

import java.util.Set;

/** 内置确定性结构 Provider，只接受空配置对象。 */
final class StructuralKnowledgeChunkerProvider implements KnowledgeChunkerProvider {

    @Override
    public String id() {
        return KnowledgeChunkerFactory.STRUCTURAL;
    }

    @Override
    public String version() {
        return StructuralKnowledgeChunker.VERSION;
    }

    @Override
    public String unavailableReason() {
        return "";
    }

    @Override
    public Set<ParserOutputCapability> requiredParserCapabilities() {
        return Set.of(ParserOutputCapability.STANDARD_ELEMENTS);
    }

    @Override
    public String defaultConfigurationJson() {
        return "{}";
    }

    @Override
    public ChunkBoundaryStrategy create(ChunkerConfiguration configuration) {
        if (!"{}".equals(configuration.providerConfigurationJson())) {
            throw new IllegalArgumentException(
                    "STRUCTURAL providerConfigurationJson must be canonical {}"
            );
        }
        return new StructuralBoundaryStrategy();
    }
}
