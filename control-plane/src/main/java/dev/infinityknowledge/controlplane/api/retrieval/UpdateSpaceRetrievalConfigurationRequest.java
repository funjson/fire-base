package dev.infinityknowledge.controlplane.api.retrieval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 追加 Space 检索配置修订的强类型请求。
 *
 * @param expectedRevision 页面读取到的当前修订
 * @param configuration 要物化为下一修订的完整配置
 */
public record UpdateSpaceRetrievalConfigurationRequest(
        @Min(1) long expectedRevision,
        @NotNull @Valid RetrievalConfigurationDto configuration
) {
}
