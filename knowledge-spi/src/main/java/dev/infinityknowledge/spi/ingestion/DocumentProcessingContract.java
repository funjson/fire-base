package dev.infinityknowledge.spi.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Space 创建时固化的实际文档处理实现合同。
 *
 * <p>用户配置中的 Adapter ID 和参数只表达选择意图；本对象进一步保存当时部署实际
 * 执行的 Pipeline、来源规范化规则、逐格式 Parser、Cleaner 与 Chunker/Tokenizer
 * 完整合同。升级只允许检测并拒绝漂移，不提供同 ID 历史实现路由。</p>
 *
 * @param pipelineContract 抽取主线实现合同
 * @param normalizerSchemaContract 所有允许入口的来源规范化规则集合合同
 * @param parserContracts 规范媒体类型到实际 Parser 实现合同的有序映射
 * @param cleanerContract 实际 Cleaner 实现及有效参数合同
 * @param chunkerContract 实际 Chunker、Tokenizer、模型与预算合同
 * @param fingerprint 上述完整材料的 SHA-256
 */
public record DocumentProcessingContract(
        String pipelineContract,
        String normalizerSchemaContract,
        Map<String, String> parserContracts,
        String cleanerContract,
        String chunkerContract,
        String fingerprint
) {

    private static final int MAX_COMPONENT_LENGTH = 65_536;

    /** 规范化完整合同，并拒绝与组件材料不一致的外部指纹。 */
    public DocumentProcessingContract {
        pipelineContract = required(
                pipelineContract,
                "pipelineContract",
                128
        );
        normalizerSchemaContract = required(
                normalizerSchemaContract,
                "normalizerSchemaContract",
                2_048
        );
        parserContracts = normalizedParserContracts(parserContracts);
        cleanerContract = required(
                cleanerContract,
                "cleanerContract",
                MAX_COMPONENT_LENGTH
        );
        chunkerContract = required(
                chunkerContract,
                "chunkerContract",
                MAX_COMPONENT_LENGTH
        );
        fingerprint = requiredFingerprint(fingerprint);
        String actual = fingerprint(
                pipelineContract,
                normalizerSchemaContract,
                parserContracts,
                cleanerContract,
                chunkerContract
        );
        if (!actual.equals(fingerprint)) {
            throw new IllegalArgumentException(
                    "document processing contract fingerprint does not match components"
            );
        }
    }

    /** 从实际组件合同生成不可伪造的总指纹。 */
    public static DocumentProcessingContract create(
            String pipelineContract,
            String normalizerSchemaContract,
            Map<String, String> parserContracts,
            String cleanerContract,
            String chunkerContract
    ) {
        Map<String, String> normalized = normalizedParserContracts(parserContracts);
        return new DocumentProcessingContract(
                pipelineContract,
                normalizerSchemaContract,
                normalized,
                cleanerContract,
                chunkerContract,
                fingerprint(
                        pipelineContract,
                        normalizerSchemaContract,
                        normalized,
                        cleanerContract,
                        chunkerContract
                )
        );
    }

    private static Map<String, String> normalizedParserContracts(
            Map<String, String> values
    ) {
        Objects.requireNonNull(values, "parserContracts must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("parserContracts must not be empty");
        }
        Map<String, String> sorted = new TreeMap<>();
        values.forEach((mediaType, contract) -> {
            String normalizedMediaType = required(mediaType, "mediaType", 128)
                    .toLowerCase(java.util.Locale.ROOT);
            if (sorted.putIfAbsent(
                    normalizedMediaType,
                    required(contract, "parserContract", MAX_COMPONENT_LENGTH)
            ) != null) {
                throw new IllegalArgumentException(
                        "parserContracts contains a duplicate mediaType"
                );
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String fingerprint(
            String pipelineContract,
            String normalizerSchemaContract,
            Map<String, String> parserContracts,
            String cleanerContract,
            String chunkerContract
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, required(pipelineContract, "pipelineContract", 128));
        append(canonical, required(
                normalizerSchemaContract,
                "normalizerSchemaContract",
                2_048
        ));
        normalizedParserContracts(parserContracts).forEach((mediaType, contract) -> {
            append(canonical, mediaType);
            append(canonical, contract);
        });
        append(canonical, required(
                cleanerContract,
                "cleanerContract",
                MAX_COMPONENT_LENGTH
        ));
        append(canonical, required(
                chunkerContract,
                "chunkerContract",
                MAX_COMPONENT_LENGTH
        ));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.toString().getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static String required(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return normalized;
    }

    private static String requiredFingerprint(String value) {
        String normalized = required(value, "fingerprint", 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "fingerprint must be a lowercase SHA-256"
            );
        }
        return normalized;
    }
}
