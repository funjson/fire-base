package dev.infinityknowledge.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 限制本地文件连接器可读取的目录和单轮资源预算。
 *
 * @param allowedRoots 逗号分隔的本地 Vault 根目录白名单
 * @param batchSize 单批记录数
 * @param maxFileBytes 单文件最大字节数
 */
@ConfigurationProperties(prefix = "infinity.knowledge.connectors.obsidian")
public record ConnectorProperties(
        String allowedRoots,
        int batchSize,
        long maxFileBytes
) {

    /**
     * 校验连接器资源预算。
     */
    public ConnectorProperties {
        allowedRoots = allowedRoots == null ? "" : allowedRoots.strip();
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        }
        if (maxFileBytes < 1 || maxFileBytes > 100_000_000L) {
            throw new IllegalArgumentException(
                    "maxFileBytes must be between 1 and 100000000"
            );
        }
    }

    /**
     * 返回绝对规范化的允许目录。
     *
     * @return 目录白名单
     */
    public List<Path> normalizedAllowedRoots() {
        if (allowedRoots.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedRoots.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }
}
