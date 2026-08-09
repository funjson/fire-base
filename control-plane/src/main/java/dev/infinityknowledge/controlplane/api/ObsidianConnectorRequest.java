package dev.infinityknowledge.controlplane.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建一个受目录白名单约束的本地 Obsidian Vault 连接器。
 */
public record ObsidianConnectorRequest(
        @NotBlank @Size(max = 128) String connectorId,
        @NotBlank @Size(max = 64) String spaceId,
        @NotBlank @Size(max = 256) String displayName,
        @NotBlank @Size(max = 256) String vaultName,
        @NotBlank @Size(max = 2048) String vaultPath,
        @Min(0) @Max(100) int authority
) {
}
