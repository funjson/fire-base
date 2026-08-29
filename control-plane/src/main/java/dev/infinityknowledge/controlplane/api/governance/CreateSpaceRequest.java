package dev.infinityknowledge.controlplane.api.governance;

import dev.infinityknowledge.controlplane.api.document.DocumentProcessingConfigRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建租户知识空间的请求。
 *
 * @param spaceId 空间标识
 * @param name 显示名称
 * @param description 空间用途描述，供 Space Router 和人工检索判断使用
 * @param documentProcessingConfig 创建后不可修改的文档处理与索引配置
 */
public record CreateSpaceRequest(
        @NotBlank @Size(max = 64) String spaceId,
        @NotBlank @Size(max = 256) String name,
        @NotBlank @Size(max = 2000) String description,
        @NotNull @Valid DocumentProcessingConfigRequest documentProcessingConfig
) {
}
