package dev.infinityknowledge.controlplane.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建租户知识空间的请求。
 *
 * @param spaceId 空间标识
 * @param name 显示名称
 */
public record CreateSpaceRequest(
        @NotBlank @Size(max = 64) String spaceId,
        @NotBlank @Size(max = 256) String name
) {
}
