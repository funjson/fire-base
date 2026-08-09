package dev.infinityknowledge.controlplane.api;

import java.util.List;
import java.util.UUID;

/**
 * 文档写入响应。
 *
 * @param documentId 文档标识
 * @param revisionId 活动修订
 * @param changed 是否改变活动修订、生命周期或需投影的文档字段
 * @param elementCount 结构元素数
 * @param chunkCount Chunk 数
 * @param vectorStatus 向量投影接纳状态
 * @param warnings 稳定降级提示
 */
public record MarkdownDocumentResponse(
        UUID documentId,
        UUID revisionId,
        boolean changed,
        int elementCount,
        int chunkCount,
        String vectorStatus,
        List<String> warnings
) {

    /**
     * Defensively copies projection warnings.
     */
    public MarkdownDocumentResponse {
        warnings = List.copyOf(warnings);
    }
}
