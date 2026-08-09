package dev.infinityknowledge.controlplane.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 通过 API 写入 Markdown 文档的请求。
 *
 * @param spaceId 知识空间
 * @param externalId 调用方稳定外部标识
 * @param title 标题
 * @param sourceUri 可审计来源
 * @param language BCP 47 语言标签
 * @param authority 权威等级
 * @param content Markdown 正文
 * @param metadata 非敏感元数据
 */
public record MarkdownDocumentRequest(
        @NotBlank @Size(max = 64) String spaceId,
        @NotBlank @Size(max = 512) String externalId,
        @NotBlank @Size(max = 512) String title,
        @NotBlank @Size(max = 2048) String sourceUri,
        @NotBlank @Size(max = 32) String language,
        @Min(0) @Max(100) int authority,
        @NotBlank @Size(max = 2_000_000) String content,
        @Size(max = 64) Map<
                @NotBlank @Size(max = 128) String,
                @NotBlank @Size(max = 2_048) String
        > metadata
) {
}
