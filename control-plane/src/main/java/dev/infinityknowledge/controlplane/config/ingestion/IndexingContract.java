package dev.infinityknowledge.controlplane.config.ingestion;

import dev.infinityknowledge.ingestion.IngestionIdentity;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;

import java.util.Objects;

/**
 * 描述一个知识空间创建时固化处理合同对应的索引代际契约。
 *
 * <p>索引代际是空间级概念，不能使用某一份 DocumentRevision 的
 * {@code processorVersion}：同一空间本来就可以同时包含 Markdown、PDF、DOCX 等
 * 不同 Parser。这里汇总空间实际选择的 Parser、来源规范化/清洗规则和 Chunker，变化时由
 * {@link dev.infinityknowledge.spi.indexing.IndexProjectionStore} 拒绝把新旧处理结果
 * 混入同一个活动代际。</p>
 */
public record IndexingContract(String normalizerVersion, String chunkerVersion) {

    private static final int FINGERPRINT_LENGTH = 48;

    /**
     * 校验可持久化到 {@code index_generation} 的短版本标识。
     */
    public IndexingContract {
        normalizerVersion = requiredVersion(normalizerVersion, "normalizerVersion");
        chunkerVersion = requiredVersion(chunkerVersion, "chunkerVersion");
    }

    /**
     * 只根据 Space 创建时固化的实际实现合同生成索引代际合同。
     *
     * <p>投影已有修订不要求旧 Adapter 仍安装，也不能在升级后用当前部署合同冒充
     * 这些修订的创建合同。</p>
     */
    public static IndexingContract fromProcessingContract(
            DocumentProcessingContract contract
    ) {
        Objects.requireNonNull(contract, "contract must not be null");
        StringBuilder normalizationMaterial = new StringBuilder();
        append(normalizationMaterial, contract.pipelineContract());
        append(normalizationMaterial, contract.normalizerSchemaContract());
        contract.parserContracts().forEach((mediaType, parserContract) -> {
            append(normalizationMaterial, mediaType);
            append(normalizationMaterial, parserContract);
        });
        append(normalizationMaterial, contract.cleanerContract());
        return new IndexingContract(
                fingerprint("normalizer-v2", normalizationMaterial.toString()),
                fingerprint("chunker-v2", contract.chunkerContract())
        );
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String requiredVersion(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException(name + " must contain 1 to 64 characters");
        }
        return normalized;
    }

    private static String fingerprint(String prefix, String material) {
        return prefix + "-" + IngestionIdentity.sha256(
                Objects.requireNonNull(material, "material must not be null")
        ).substring(0, FINGERPRINT_LENGTH);
    }
}
