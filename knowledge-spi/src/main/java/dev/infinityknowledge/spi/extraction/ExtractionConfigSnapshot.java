package dev.infinityknowledge.spi.extraction;

import dev.infinityknowledge.spi.ingestion.SpaceDocumentProcessingConfigStore.CleaningAction;
import dev.infinityknowledge.spi.ingestion.DocumentProcessingContract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一次抽取任务实际绑定的不可变处理配置。
 *
 * <p>快照不包含 Space 配置的固定存储版本、创建主体或固化时间；这些审计属性不会改变处理语义，不能
 * 污染 {@code fingerprint}。完整实际实现合同与用户配置共同构成有效语义，供历史
 * 审计及基准运行与测试运行对比；本系统不尝试路由到合同中的历史实现。</p>
 */
public record ExtractionConfigSnapshot(
        DocumentProcessingContract processingContract,
        String normalizerContract,
        Map<String, String> parserSelections,
        Cleaning cleaning,
        Chunker chunker,
        String fingerprint
) {

    /** 保存规范化、有序且可稳定序列化的配置。 */
    public ExtractionConfigSnapshot {
        Objects.requireNonNull(
                processingContract,
                "processingContract must not be null"
        );
        normalizerContract = required(
                normalizerContract,
                "normalizerContract",
                128
        );
        Objects.requireNonNull(parserSelections, "parserSelections must not be null");
        if (parserSelections.isEmpty()) {
            throw new IllegalArgumentException("parserSelections must not be empty");
        }
        Map<String, String> normalized = new TreeMap<>();
        parserSelections.forEach((mediaType, parserId) -> {
            String normalizedMediaType = required(mediaType, "mediaType", 128)
                    .toLowerCase(java.util.Locale.ROOT);
            if (normalized.putIfAbsent(
                    normalizedMediaType,
                    required(parserId, "parserId", 128)
            ) != null) {
                throw new IllegalArgumentException(
                        "parserSelections contains a duplicate mediaType"
                );
            }
        });
        parserSelections = Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
        Objects.requireNonNull(cleaning, "cleaning must not be null");
        Objects.requireNonNull(chunker, "chunker must not be null");
        fingerprint = sha256(fingerprint, "fingerprint");
    }

    /** Cleaner 对文档家具和前置元数据的确定性处理动作。 */
    public record Cleaning(
            CleaningAction header,
            CleaningAction footer,
            CleaningAction pageNumber,
            CleaningAction watermark,
            CleaningAction frontMatter
    ) {
        /** 所有角色都必须显式选择，不能依赖读取时默认值。 */
        public Cleaning {
            Objects.requireNonNull(header, "header must not be null");
            Objects.requireNonNull(footer, "footer must not be null");
            Objects.requireNonNull(pageNumber, "pageNumber must not be null");
            Objects.requireNonNull(watermark, "watermark must not be null");
            Objects.requireNonNull(frontMatter, "frontMatter must not be null");
        }
    }

    /** Chunker 与 Tokenizer 的完整有效参数。 */
    public record Chunker(
            String providerId,
            String tokenizerId,
            int minimumTokens,
            int targetTokens,
            int maximumTokens,
            int overlapTokens,
            String providerConfigurationJson
    ) {
        /** 快照只接受已经由配置层校验过的规范值。 */
        public Chunker {
            providerId = required(providerId, "providerId", 64);
            tokenizerId = required(tokenizerId, "tokenizerId", 128);
            if (minimumTokens < 1 || targetTokens < minimumTokens
                    || maximumTokens < targetTokens || maximumTokens > 65_536
                    || overlapTokens < 0
                    || overlapTokens >= minimumTokens) {
                throw new IllegalArgumentException("chunker token limits are inconsistent");
            }
            providerConfigurationJson = required(
                    providerConfigurationJson,
                    "providerConfigurationJson",
                    65_536
            );
        }
    }

    private static String required(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return normalized;
    }

    private static String sha256(String value, String name) {
        String normalized = required(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }
}
