package dev.infinityknowledge.controlplane.application.ingestion;

import dev.infinityknowledge.ingestion.chunking.KnowledgeChunkerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 在控制面配置对象与持久化 canonical JSON 之间执行唯一映射。
 *
 * <p>SPI 只保存中立 JSON，不认识具体算法字段；这里负责拒绝内置 Provider 的
 * 未知字段并保证键顺序稳定。运行时 Provider 仍会执行最终业务校验。</p>
 */
public final class ChunkerProviderConfigurationJson {
    private static final Set<String> SEMANTIC_KEYS = Set.of(
            "contextSlices",
            "embeddingProfileId",
            "mergeSimilarityThreshold",
            "splitSimilarityThreshold"
    );
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ChunkerProviderConfigurationJson() {
    }

    /** 校验配置对象并生成稳定键序的 JSON Object。 */
    public static String encode(
            String providerId,
            Map<String, Object> rawConfiguration
    ) {
        String normalizedProviderId = Objects.requireNonNull(
                providerId,
                "providerId must not be null"
        ).strip().toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> configuration = normalized(rawConfiguration);
        if (KnowledgeChunkerFactory.STRUCTURAL.equals(normalizedProviderId)) {
            if (!configuration.isEmpty()) {
                throw new IllegalArgumentException(
                        "STRUCTURAL providerConfig must be an empty object"
                );
            }
            return "{}";
        }
        if (KnowledgeChunkerFactory.SEMANTIC_REFINEMENT.equals(normalizedProviderId)) {
            configuration = semanticConfiguration(configuration);
        }
        try {
            return JSON.writeValueAsString(configuration);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException(
                    "chunker providerConfig is not valid JSON data",
                    failure
            );
        }
    }

    /** 把存储中的 canonical JSON 还原为只含 JSON 标量的配置对象。 */
    public static Map<String, Object> decode(String value) {
        try {
            Map<String, Object> decoded = JSON.readValue(
                    value,
                    new TypeReference<Map<String, Object>>() { }
            );
            return Map.copyOf(normalized(decoded));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "stored chunker provider configuration is invalid",
                    failure
            );
        }
    }

    private static Map<String, Object> normalized(Map<String, Object> values) {
        Objects.requireNonNull(values, "providerConfig must not be null");
        Map<String, Object> normalized = new TreeMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = Objects.requireNonNull(
                    key,
                    "providerConfig key must not be null"
            ).strip();
            if (normalizedKey.isEmpty() || normalizedKey.length() > 128) {
                throw new IllegalArgumentException(
                        "providerConfig key is blank or too long"
                );
            }
            if (!(value instanceof String) && !(value instanceof Number)
                    && !(value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "providerConfig values must be non-null JSON scalars"
                );
            }
            if (normalized.put(normalizedKey, value) != null) {
                throw new IllegalArgumentException(
                        "providerConfig contains duplicate normalized keys"
                );
            }
        });
        return normalized;
    }

    private static Map<String, Object> semanticConfiguration(
            Map<String, Object> values
    ) {
        if (!values.keySet().equals(SEMANTIC_KEYS)) {
            throw new IllegalArgumentException(
                    "SEMANTIC_REFINEMENT providerConfig has unsupported or missing fields"
            );
        }
        String embeddingProfileId = requiredText(
                values.get("embeddingProfileId"),
                "embeddingProfileId"
        );
        if (embeddingProfileId.length() > 256) {
            throw new IllegalArgumentException("embeddingProfileId is too long");
        }
        int contextSlices = requiredInteger(
                values.get("contextSlices"),
                "contextSlices"
        );
        if (contextSlices < 0 || contextSlices > 2) {
            throw new IllegalArgumentException(
                    "contextSlices must be between 0 and 2"
            );
        }
        double splitThreshold = requiredFiniteNumber(
                values.get("splitSimilarityThreshold"),
                "splitSimilarityThreshold"
        );
        double mergeThreshold = requiredFiniteNumber(
                values.get("mergeSimilarityThreshold"),
                "mergeSimilarityThreshold"
        );
        if (splitThreshold < -1.0D || mergeThreshold > 1.0D
                || splitThreshold >= mergeThreshold) {
            throw new IllegalArgumentException(
                    "semantic thresholds must satisfy -1 <= split < merge <= 1"
            );
        }
        return normalized(Map.of(
                "contextSlices", contextSlices,
                "embeddingProfileId", embeddingProfileId,
                "mergeSimilarityThreshold", mergeThreshold,
                "splitSimilarityThreshold", splitThreshold
        ));
    }

    private static String requiredText(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        if (!text.equals(text.strip())) {
            throw new IllegalArgumentException(name + " must not contain outer whitespace");
        }
        return text;
    }

    private static int requiredInteger(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        double rawValue = number.doubleValue();
        if (!Double.isFinite(rawValue) || rawValue != Math.rint(rawValue)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return Math.toIntExact(number.longValue());
    }

    private static double requiredFiniteNumber(Object value, String name) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return number.doubleValue();
    }
}
