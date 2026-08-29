package dev.infinityknowledge.domain.document;

import java.util.Objects;
import java.util.UUID;

/**
 * 一个 Element 在规范化文档制品中的精确文本来源。
 *
 * <p>范围必须对应 Element 正文的完整 UTF-16 字符序列。页码只是富文档 Parser
 * 可靠提供时的辅助定位，不替代规范化文本范围，也不得由调用方估算。</p>
 *
 * @param elementId 来源元素标识
 * @param artifactId 规范化制品标识
 * @param startOffset 制品文本内起始偏移，含该位置
 * @param endOffset 制品文本内结束偏移，不含该位置
 * @param pageNumber 可选的一基页码
 */
public record ElementProvenance(
        UUID elementId,
        UUID artifactId,
        int startOffset,
        int endOffset,
        Integer pageNumber
) {

    /** Parser 边界尚未物化为本类型前，唯一允许使用的标准页码属性名。 */
    public static final String PAGE_NUMBER_ATTRIBUTE = "pageNumber";

    /** 校验非空半开区间及可靠页码边界。 */
    public ElementProvenance {
        Objects.requireNonNull(elementId, "elementId must not be null");
        Objects.requireNonNull(artifactId, "artifactId must not be null");
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException(
                    "element provenance offsets must form a non-empty range"
            );
        }
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive when present");
        }
    }
}
