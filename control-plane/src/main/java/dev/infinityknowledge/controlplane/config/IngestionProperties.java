package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 绑定知识切分预算，避免摄取服务中出现不可调整的生产参数。
 *
 * @param tokenizerId 默认 Token Counter 稳定标识
 * @param minimumChunkTokens 常规知识块最小预算
 * @param targetChunkTokens 常规知识块目标预算
 * @param maximumChunkTokens 单个知识块最大预算
 * @param overlapChunkTokens 相邻知识块重叠预算
 * @param allowedSourceSchemes 可审计来源 URI 的协议白名单
 */
@ConfigurationProperties(prefix = "infinity.knowledge.ingestion")
public record IngestionProperties(
        String tokenizerId,
        int minimumChunkTokens,
        int targetChunkTokens,
        int maximumChunkTokens,
        int overlapChunkTokens,
        List<String> allowedSourceSchemes
) {
    private static final Pattern URI_SCHEME = Pattern.compile(
            "[a-z][a-z0-9+.-]*"
    );
    private static final Pattern TOKEN_COUNTER_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"
    );

    /**
     * 校验部署默认切分参数。
     */
    public IngestionProperties {
        tokenizerId = tokenizerId == null ? "" : tokenizerId.strip();
        if (!TOKEN_COUNTER_ID.matcher(tokenizerId).matches()) {
            throw new IllegalArgumentException(
                    "tokenizerId has invalid format"
            );
        }
        if (minimumChunkTokens < 1 || minimumChunkTokens > targetChunkTokens
                || targetChunkTokens > maximumChunkTokens
                || maximumChunkTokens > 65_536) {
            throw new IllegalArgumentException(
                    "chunk token budgets must satisfy 1 <= minimum <= target "
                            + "<= maximum <= 65536"
            );
        }
        if (overlapChunkTokens < 0 || overlapChunkTokens >= minimumChunkTokens) {
            throw new IllegalArgumentException(
                    "overlapChunkTokens must be non-negative and less than minimum"
            );
        }
        allowedSourceSchemes = allowedSourceSchemes == null
                ? List.of()
                : allowedSourceSchemes.stream()
                        .map(String::strip)
                        .filter(value -> !value.isBlank())
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
        if (allowedSourceSchemes.isEmpty()) {
            throw new IllegalArgumentException("allowedSourceSchemes must not be empty");
        }
        if (allowedSourceSchemes.stream().anyMatch(value ->
                !URI_SCHEME.matcher(value).matches())) {
            throw new IllegalArgumentException(
                    "allowedSourceSchemes contains an invalid URI scheme"
            );
        }
    }

    /**
     * 判断来源 URI 是否为绝对 URI 且协议已获批准。
     *
     * @param value 来源 URI
     * @return 是否允许进入知识事实源
     */
    public boolean allowsSourceUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!allowedSourceSchemes.contains(scheme)) {
                return false;
            }
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return uri.getHost() != null && uri.getUserInfo() == null;
            }
            return uri.getSchemeSpecificPart() != null
                    && !uri.getSchemeSpecificPart().isBlank();
        } catch (IllegalArgumentException invalidUri) {
            return false;
        }
    }
}
