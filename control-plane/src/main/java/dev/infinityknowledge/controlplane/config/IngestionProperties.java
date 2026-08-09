package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 绑定知识切分预算，避免摄取服务中出现不可调整的生产参数。
 *
 * @param targetChunkCharacters 常规知识块目标字符数
 * @param maximumChunkCharacters 单个知识块最大字符数
 * @param allowedSourceSchemes 可审计来源 URI 的协议白名单
 */
@ConfigurationProperties(prefix = "infinity.knowledge.ingestion")
public record IngestionProperties(
        int targetChunkCharacters,
        int maximumChunkCharacters,
        List<String> allowedSourceSchemes
) {
    private static final Pattern URI_SCHEME = Pattern.compile(
            "[a-z][a-z0-9+.-]*"
    );

    /**
     * 校验切分参数。
     */
    public IngestionProperties {
        if (targetChunkCharacters < 128 || targetChunkCharacters > 20_000) {
            throw new IllegalArgumentException(
                    "targetChunkCharacters must be between 128 and 20000"
            );
        }
        if (maximumChunkCharacters < targetChunkCharacters
                || maximumChunkCharacters > 50_000) {
            throw new IllegalArgumentException(
                    "maximumChunkCharacters must be between target and 50000"
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
