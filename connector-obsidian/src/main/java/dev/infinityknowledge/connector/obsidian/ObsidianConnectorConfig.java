package dev.infinityknowledge.connector.obsidian;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.nio.file.Path;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/**
 * 保存一个 Obsidian Vault 连接器实例的只读扫描配置。
 *
 * @param connectorId 稳定连接器标识
 * @param vaultName Vault 展示名称
 * @param vaultRoot Vault 根目录
 * @param maxFileBytes 单个 Markdown 最大字节数
 * @param ignoredDirectoryNames 忽略目录名称
 */
public record ObsidianConnectorConfig(
        String connectorId,
        String vaultName,
        Path vaultRoot,
        long maxFileBytes,
        Set<String> ignoredDirectoryNames
) {

    /**
     * 校验 Vault 路径和文件预算，并转为绝对规范化路径。
     */
    public ObsidianConnectorConfig {
        connectorId = DomainChecks.requiredText(connectorId, "connectorId", 128);
        vaultName = DomainChecks.requiredText(vaultName, "vaultName", 256);
        Objects.requireNonNull(vaultRoot, "vaultRoot must not be null");
        try {
            vaultRoot = vaultRoot.toRealPath();
        } catch (IOException invalidRoot) {
            throw new IllegalArgumentException(
                    "vaultRoot must be an existing readable directory",
                    invalidRoot
            );
        }
        if (maxFileBytes < 1 || maxFileBytes > 100_000_000L) {
            throw new IllegalArgumentException("maxFileBytes must be between 1 and 100000000");
        }
        ignoredDirectoryNames = Set.copyOf(
                Objects.requireNonNull(
                        ignoredDirectoryNames,
                        "ignoredDirectoryNames must not be null"
                )
        );
    }
}
