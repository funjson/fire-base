package dev.infinityknowledge.ingestion.chunking;

import dev.infinityknowledge.domain.document.ElementProvenance;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 把有序 Element 转换为满足单 Slice Token 硬上限的精确原文片段。 */
final class ElementSlicer {
    /** 自然边界与硬容量切片规则版本，必须进入处理契约。 */
    static final String VERSION = "element-slicer-v2";

    private ElementSlicer() {
    }

    /**
     * 校验修订与顺序，并在自然文本边界附近拆分超大 Element。
     */
    static List<ElementSlice> slice(
            UUID revisionId,
            List<KnowledgeElement> elements,
            ChunkSizing sizing
    ) {
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(sizing, "sizing must not be null");
        elements = List.copyOf(Objects.requireNonNull(elements, "elements must not be null"));
        validateElementSequence(revisionId, elements);

        List<ElementSlice> slices = new ArrayList<>();
        for (KnowledgeElement element : elements) {
            addElementSlices(slices, element, sizing);
        }
        return List.copyOf(slices);
    }

    private static void validateElementSequence(
            UUID revisionId,
            List<KnowledgeElement> elements
    ) {
        int previousOrdinal = -1;
        java.util.Set<UUID> elementIds = new java.util.HashSet<>();
        for (KnowledgeElement element : elements) {
            Objects.requireNonNull(element, "element must not be null");
            if (!revisionId.equals(element.revisionId())) {
                throw new IllegalArgumentException(
                        "element revision does not match requested revision"
                );
            }
            if (element.ordinal() <= previousOrdinal) {
                throw new IllegalArgumentException(
                        "element ordinals must be strictly increasing and unique"
                );
            }
            if (!elementIds.add(element.id())) {
                throw new IllegalArgumentException(
                        "element ids must be unique within one revision"
                );
            }
            previousOrdinal = element.ordinal();
        }
    }

    private static void addElementSlices(
            List<ElementSlice> slices,
            KnowledgeElement element,
            ChunkSizing sizing
    ) {
        String content = element.content();
        Integer pageNumber = pageNumber(element);
        List<TextRange> logicalRanges = switch (element.type()) {
            case PARAGRAPH -> sentenceRanges(content);
            case LIST, TABLE, CODE -> lineRanges(content);
            default -> List.of(new TextRange(0, content.length()));
        };
        for (TextRange range : logicalRanges) {
            addBoundedRange(slices, element, range, pageNumber, sizing);
        }
    }

    /** 普通段落先形成句子 Slice，语义策略才能在 Element 内双向调整边界。 */
    private static List<TextRange> sentenceRanges(String content) {
        List<TextRange> ranges = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\n' || character == '。' || character == '.'
                    || character == '！' || character == '？'
                    || character == '!' || character == '?') {
                int end = index + 1;
                if (addLosslessRange(ranges, content, start, end)) {
                    start = end;
                }
            }
        }
        addFinalLosslessRange(ranges, content, start);
        return ranges.isEmpty()
                ? List.of(new TextRange(0, content.length()))
                : List.copyOf(ranges);
    }

    /** 列表和表格按解析器保留的行或项形成 Slice。 */
    private static List<TextRange> lineRanges(String content) {
        List<TextRange> ranges = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n') {
                int end = index + 1;
                if (addLosslessRange(ranges, content, start, end)) {
                    start = end;
                }
            }
        }
        addFinalLosslessRange(ranges, content, start);
        return ranges.isEmpty()
                ? List.of(new TextRange(0, content.length()))
                : List.copyOf(ranges);
    }

    /** 只在范围含正文时提交，前导或连续空白会自然并入下一范围。 */
    private static boolean addLosslessRange(
            List<TextRange> ranges,
            String content,
            int start,
            int end
    ) {
        if (content.substring(start, end).isBlank()) {
            return false;
        }
        ranges.add(new TextRange(start, end));
        return true;
    }

    /** 最后一个范围吸收尾随空白，保证所有 Slice 连续覆盖 Element 原文。 */
    private static void addFinalLosslessRange(
            List<TextRange> ranges,
            String content,
            int start
    ) {
        if (start >= content.length()) {
            return;
        }
        if (content.substring(start).isBlank() && !ranges.isEmpty()) {
            TextRange previous = ranges.removeLast();
            ranges.add(new TextRange(previous.startOffset(), content.length()));
            return;
        }
        ranges.add(new TextRange(start, content.length()));
    }

    private static void addBoundedRange(
            List<ElementSlice> slices,
            KnowledgeElement element,
            TextRange range,
            Integer pageNumber,
            ChunkSizing sizing
    ) {
        int start = range.startOffset();
        boolean continuation = false;
        while (start < range.endOffset()) {
            ElementSlice slice = maximumContextualSlice(
                    element,
                    start,
                    range.endOffset(),
                    pageNumber,
                    continuation,
                    sizing
            );
            slices.add(slice);
            continuation = true;
            start = slice.endOffset();
        }
    }

    /**
     * 按最终 contextualText 而非裸正文求单 Slice 上限；标题路径过长到连一个
     * Unicode Code Point 都无法容纳时明确失败，禁止产出超模型预算的 Chunk。
     */
    private static ElementSlice maximumContextualSlice(
            KnowledgeElement element,
            int start,
            int rangeEnd,
            Integer pageNumber,
            boolean continuation,
            ChunkSizing sizing
    ) {
        int candidateEnd = sizing.tokenCounter().maximumPrefixEnd(
                element.content(),
                start,
                rangeEnd,
                sizing.maximumTokens()
        );
        if (candidateEnd == start) {
            throw new IllegalStateException(
                    "token counter cannot fit one Unicode code point within maximumTokens"
            );
        }
        if (candidateEnd < rangeEnd) {
            candidateEnd = naturalBoundary(
                    element.type(),
                    element.content(),
                    start,
                    candidateEnd
            );
        }
        while (candidateEnd > start) {
            ElementSlice candidate = new ElementSlice(
                    element,
                    start,
                    candidateEnd,
                    pageNumber,
                    continuation
            );
            String contextualText = ElementSlice.contextualText(List.of(candidate));
            if (sizing.tokenCounter().count(contextualText) <= sizing.maximumTokens()) {
                return candidate;
            }
            int contextualPrefixEnd = sizing.tokenCounter().maximumPrefixEnd(
                    contextualText,
                    0,
                    contextualText.length(),
                    sizing.maximumTokens()
            );
            int contentStart = contextualText.length() - candidate.content().length();
            if (contextualPrefixEnd <= contentStart) {
                throw new IllegalStateException(
                        "section context leaves no room for one content code point"
                );
            }
            int hardEnd = start + contextualPrefixEnd - contentStart;
            int nextEnd = naturalBoundary(element.type(), element.content(), start, hardEnd);
            if (nextEnd >= candidateEnd) {
                throw new IllegalStateException(
                        "token counter maximumPrefixEnd did not reduce an oversized slice"
                );
            }
            candidateEnd = nextEnd;
        }
        throw new IllegalStateException(
                "token counter cannot fit one Unicode code point within maximumTokens"
        );
    }

    /** 在硬上限前优先选择后半段的换行或句末标点。 */
    private static int naturalBoundary(
            ElementType type,
            String content,
            int start,
            int hardEnd
    ) {
        if (type != ElementType.PARAGRAPH) {
            return hardEnd;
        }
        if (hardEnd == content.length()) {
            return hardEnd;
        }
        int minimum = start + Math.max(1, (hardEnd - start) / 2);
        for (int index = hardEnd; index > minimum; index--) {
            char character = content.charAt(index - 1);
            if (character == '\n' || character == '。' || character == '.'
                    || character == '！' || character == '？'
                    || character == '!' || character == '?') {
                return index;
            }
        }
        return hardEnd;
    }

    private static Integer pageNumber(KnowledgeElement element) {
        String raw = element.attributes().get(ElementProvenance.PAGE_NUMBER_ATTRIBUTE);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Element 原文内一个非空逻辑范围。 */
    private record TextRange(int startOffset, int endOffset) {
    }
}
