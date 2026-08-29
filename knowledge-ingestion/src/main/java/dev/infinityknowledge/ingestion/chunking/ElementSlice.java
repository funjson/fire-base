package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.ChunkSourceSpan;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个 Element 中可被规划和物化的连续原文片段。
 *
 * <p>它是结构规划与 Chunk 物化之间唯一的正文载体。语义模型只能建议 Slice 之间
 * 的边界，不能改写正文或伪造 SourceSpan。</p>
 *
 * @param element 来源结构元素
 * @param startOffset Element 正文内起始 UTF-16 偏移，含该位置
 * @param endOffset Element 正文内结束 UTF-16 偏移，不含该位置
 * @param pageNumber 可选的一基页码
 * @param hardBoundaryBefore 是否因同一 Element 内的硬容量拆分而禁止与前片段合并
 */
public record ElementSlice(
        KnowledgeElement element,
        int startOffset,
        int endOffset,
        Integer pageNumber,
        boolean hardBoundaryBefore
) {

    /** 校验 Slice 必须落在来源 Element 的非空正文范围内。 */
    public ElementSlice {
        element = Objects.requireNonNull(element, "element must not be null");
        if (startOffset < 0 || endOffset <= startOffset
                || endOffset > element.content().length()) {
            throw new IllegalArgumentException("slice offsets must form a valid element range");
        }
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive when present");
        }
    }

    /** 返回该 Slice 的原始正文。 */
    public String content() {
        return element.content().substring(startOffset, endOffset);
    }

    /** 返回来源 Element 标识。 */
    public UUID elementId() {
        return element.id();
    }

    /** 返回来源 Element 在修订内的顺序。 */
    public int elementOrdinal() {
        return element.ordinal();
    }

    /** 返回来源内容类型。 */
    public ElementType type() {
        return element.type();
    }

    /** 返回来源章节路径。 */
    public List<String> sectionPath() {
        return element.sectionPath();
    }

    /** 返回该片段的精确引用范围。 */
    public ChunkSourceSpan sourceSpan() {
        return new ChunkSourceSpan(element.id(), startOffset, endOffset, pageNumber);
    }

    /** 按阅读顺序连接 Slice 正文，Planner 与 Assembler 必须复用同一规则。 */
    static String joinContent(List<ElementSlice> slices) {
        StringBuilder content = new StringBuilder();
        ElementSlice previous = null;
        for (ElementSlice slice : slices) {
            if (previous != null
                    && previous.elementId().equals(slice.elementId())
                    && previous.endOffset() <= slice.startOffset()) {
                content.append(slice.element().content(), previous.endOffset(), slice.startOffset());
            } else if (previous != null) {
                content.append("\n\n");
            }
            content.append(slice.content());
            previous = slice;
        }
        return content.toString();
    }

    /**
     * 返回最终进入向量化和语义重排的检索文本。
     *
     * <p>若 Chunk 正文已经以当前 HEADING/TITLE 开头，则只补充父级路径，避免把末级
     * 标题重复两次。大小规划与最终物化必须复用本方法。</p>
     */
    static String contextualText(List<ElementSlice> slices) {
        if (slices.isEmpty()) {
            return "";
        }
        String content = joinContent(slices);
        List<String> path = new java.util.ArrayList<>(slices.getFirst().sectionPath());
        ElementSlice first = slices.getFirst();
        if ((first.type() == ElementType.HEADING || first.type() == ElementType.TITLE)
                && !path.isEmpty()
                && path.getLast().equals(first.element().content().strip())) {
            path.removeLast();
        }
        if (path.isEmpty()) {
            return content;
        }
        return String.join(" / ", path) + "\n\n" + content;
    }
}
