package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.domain.identity.TenantId;
import dev.infinityknowledge.domain.space.KnowledgeSpaceId;
import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot.Chunker;
import dev.infinityknowledge.spi.extraction.ExtractionConfigSnapshot.Cleaning;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.ChunkerConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningConfiguration;
import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.SpaceDocumentProcessingConfig;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 创建并恢复抽取任务使用的不可变处理配置快照。 */
public final class ExtractionConfigSnapshots {

    private ExtractionConfigSnapshots() {
    }

    /**
     * 从当前物化配置生成语义快照。
     *
     * <p>指纹使用长度前缀编码，避免分隔符出现在 Provider JSON 时产生歧义；配置
     * version、updatedBy 和 updatedAt 不参与计算。</p>
     */
    public static ExtractionConfigSnapshot capture(
            SpaceDocumentProcessingConfig config,
            DocumentProcessingContract processingContract,
            String normalizerContract
    ) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(
                processingContract,
                "processingContract must not be null"
        );
        if (config.processingContract() != null
                && !processingContract.equals(config.processingContract())) {
            throw new IllegalArgumentException(
                    "processingContract differs from config contract"
            );
        }
        var cleaning = config.cleaning();
        var chunker = config.chunker();
        var snapshot = new ExtractionConfigSnapshot(
                processingContract,
                Objects.requireNonNull(
                        normalizerContract,
                        "normalizerContract must not be null"
                ),
                config.parserSelections(),
                new Cleaning(
                        cleaning.header(),
                        cleaning.footer(),
                        cleaning.pageNumber(),
                        cleaning.watermark(),
                        cleaning.frontMatter()
                ),
                new Chunker(
                        chunker.providerId(),
                        chunker.tokenizerId(),
                        chunker.minimumTokens(),
                        chunker.targetTokens(),
                        chunker.maximumTokens(),
                        chunker.overlapTokens(),
                        chunker.providerConfigurationJson()
                ),
                fingerprint(config, processingContract, normalizerContract)
        );
        return snapshot;
    }

    /**
     * 恢复 Worker 必须执行的创建时配置，不再读取 Space 当前配置。
     *
     * <p>{@code configVersion} 仍保留在返回对象中，供 INGEST 发布前执行版本栅栏；
     * 纪元时间只是恢复对象的非语义审计占位，不进入任何处理合同。</p>
     */
    public static SpaceDocumentProcessingConfig restore(
            TenantId tenantId,
            KnowledgeSpaceId spaceId,
            long configVersion,
            ExtractionConfigSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (configVersion != 1L) {
            throw new IllegalArgumentException(
                    "immutable space document processing config version must be 1"
            );
        }
        var cleaning = snapshot.cleaning();
        var chunker = snapshot.chunker();
        return new SpaceDocumentProcessingConfig(
                tenantId,
                spaceId,
                snapshot.parserSelections(),
                new CleaningConfiguration(
                        cleaning.header(),
                        cleaning.footer(),
                        cleaning.pageNumber(),
                        cleaning.watermark(),
                        cleaning.frontMatter()
                ),
                new ChunkerConfiguration(
                        chunker.providerId(),
                        chunker.tokenizerId(),
                        chunker.minimumTokens(),
                        chunker.targetTokens(),
                        chunker.maximumTokens(),
                        chunker.overlapTokens(),
                        chunker.providerConfigurationJson()
                ),
                snapshot.processingContract(),
                configVersion,
                null,
                Instant.EPOCH
        );
    }

    private static String fingerprint(
            SpaceDocumentProcessingConfig config,
            DocumentProcessingContract processingContract,
            String normalizerContract
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, Objects.requireNonNull(
                processingContract,
                "processingContract"
        ).fingerprint());
        append(canonical, Objects.requireNonNull(
                normalizerContract,
                "normalizerContract"
        ));
        for (Map.Entry<String, String> parser : config.parserSelections().entrySet()) {
            append(canonical, parser.getKey());
            append(canonical, parser.getValue());
        }
        var cleaning = config.cleaning();
        append(canonical, cleaning.header().name());
        append(canonical, cleaning.footer().name());
        append(canonical, cleaning.pageNumber().name());
        append(canonical, cleaning.watermark().name());
        append(canonical, cleaning.frontMatter().name());
        var chunker = config.chunker();
        append(canonical, chunker.providerId());
        append(canonical, chunker.tokenizerId());
        append(canonical, Integer.toString(chunker.minimumTokens()));
        append(canonical, Integer.toString(chunker.targetTokens()));
        append(canonical, Integer.toString(chunker.maximumTokens()));
        append(canonical, Integer.toString(chunker.overlapTokens()));
        append(canonical, chunker.providerConfigurationJson());
        return IngestionIdentity.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }
}
