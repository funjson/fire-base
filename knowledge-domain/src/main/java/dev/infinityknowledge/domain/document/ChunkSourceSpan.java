package dev.infinityknowledge.domain.document;

import java.util.Objects;
import java.util.UUID;

/**
 * Chunk 在单个结构元素中的原始文本范围。
 *
 * <p>偏移量始终相对于 {@link KnowledgeElement#content()} 的 UTF-16 字符索引，
 * 因而可直接用于当前 Java/Web 前端的文本高亮。页码是解析器能够可靠提供时的
 * 可选辅助定位；坐标框属于未来的版面来源模型，不能伪造为文本偏移量。</p>
 *
 * @param elementId 来源元素标识
 * @param startOffset 元素正文内起始偏移，含该位置
 * @param endOffset 元素正文内结束偏移，不含该位置
 * @param pageNumber 可选的一基页码
 */
public record ChunkSourceSpan(
        UUID elementId,
        int startOffset,
        int endOffset,
        Integer pageNumber
) {

    /** 校验范围不变量，避免引用跳出原始元素正文。 */
    public ChunkSourceSpan {
        Objects.requireNonNull(elementId, "elementId must not be null");
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("source span offsets must form a non-empty range");
        }
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive when present");
        }
    }
}
