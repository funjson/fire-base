package dev.infinityknowledge.spi.extraction;

import dev.infinityknowledge.domain.document.ElementType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 管理员可查看的有界抽取预览产物。
 *
 * <p>正文只保存截断后的预览片段，完整原件仍由 OSS 管理；该对象不得写入日志。
 * Element/Chunk 数、每段文本和总文本都有硬上限，防止测试页面把数据库变成第二份
 * 原件存储。SourceRange 同时区分 Element 内偏移和规范化 Artifact 全局偏移。</p>
 */
public record ExtractionPreview(
        UUID artifactId,
        int artifactLength,
        int totalElementCount,
        int totalChunkCount,
        boolean truncated,
        List<Element> elements,
        List<Chunk> chunks
) {

    public static final int MAX_ELEMENTS = 200;
    public static final int MAX_CHUNKS = 200;
    public static final int MAX_TEXT_PER_ELEMENT = 1_000;
    public static final int MAX_TEXT_PER_CHUNK = 1_500;
    public static final int MAX_SOURCE_SPANS_PER_CHUNK = 128;
    public static final int MAX_TOTAL_TEXT = 120_000;

    /** 验证预览本身保持有界，完整产物数量允许大于展示数量。 */
    public ExtractionPreview {
        Objects.requireNonNull(artifactId, "artifactId must not be null");
        if (artifactLength < 0 || totalElementCount < 0 || totalChunkCount < 0) {
            throw new IllegalArgumentException("preview counts must be non-negative");
        }
        elements = List.copyOf(Objects.requireNonNull(elements, "elements must not be null"));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
        if (elements.size() > MAX_ELEMENTS || chunks.size() > MAX_CHUNKS
                || elements.size() > totalElementCount || chunks.size() > totalChunkCount) {
            throw new IllegalArgumentException("preview item limits are inconsistent");
        }
        long textLength = elements.stream().mapToLong(value -> value.text().length()).sum()
                + chunks.stream().mapToLong(value -> value.text().length()).sum();
        if (textLength > MAX_TOTAL_TEXT) {
            throw new IllegalArgumentException("preview total text exceeds the hard limit");
        }
        boolean actuallyTruncated = elements.size() < totalElementCount
                || chunks.size() < totalChunkCount
                || elements.stream().anyMatch(Element::textTruncated)
                || chunks.stream().anyMatch(Chunk::textTruncated);
        if (actuallyTruncated && !truncated) {
            throw new IllegalArgumentException("truncated preview must be declared");
        }
    }

    /** Element 的结构、规范化来源范围和有界正文。 */
    public record Element(
            UUID id,
            UUID parentId,
            ElementType type,
            int ordinal,
            List<String> sectionPath,
            String role,
            String cleaningAction,
            String cleaningReasonCode,
            ArtifactRange sourceRange,
            int contentLength,
            String text,
            boolean textTruncated
    ) {
        /** 保留父子关系和顺序，不复制任意 Parser attributes。 */
        public Element {
            Objects.requireNonNull(id, "element id must not be null");
            Objects.requireNonNull(type, "element type must not be null");
            if (ordinal < 0 || contentLength < 0) {
                throw new IllegalArgumentException("element preview values must be non-negative");
            }
            sectionPath = List.copyOf(Objects.requireNonNull(
                    sectionPath,
                    "sectionPath must not be null"
            ));
            cleaningAction = stableCode(cleaningAction, "cleaningAction");
            cleaningReasonCode = stableCode(cleaningReasonCode, "cleaningReasonCode");
            text = boundedText(text, MAX_TEXT_PER_ELEMENT, "element text");
            if (text.length() > contentLength || (text.length() < contentLength && !textTruncated)) {
                throw new IllegalArgumentException("element text truncation is inconsistent");
            }
        }
    }

    /** Chunk 的边界、来源 Element 范围和有界原文展示。 */
    public record Chunk(
            UUID id,
            int ordinal,
            List<String> sectionPath,
            int contentLength,
            String text,
            boolean textTruncated,
            List<ElementRange> sourceSpans
    ) {
        /** SourceSpan 为空时预览不能伪造边界。 */
        public Chunk {
            Objects.requireNonNull(id, "chunk id must not be null");
            if (ordinal < 0 || contentLength < 0) {
                throw new IllegalArgumentException("chunk preview values must be non-negative");
            }
            sectionPath = List.copyOf(Objects.requireNonNull(
                    sectionPath,
                    "sectionPath must not be null"
            ));
            text = boundedText(text, MAX_TEXT_PER_CHUNK, "chunk text");
            if (text.length() > contentLength || (text.length() < contentLength && !textTruncated)) {
                throw new IllegalArgumentException("chunk text truncation is inconsistent");
            }
            sourceSpans = List.copyOf(Objects.requireNonNull(
                    sourceSpans,
                    "sourceSpans must not be null"
            ));
            if (sourceSpans.isEmpty()
                    || sourceSpans.size() > MAX_SOURCE_SPANS_PER_CHUNK) {
                throw new IllegalArgumentException("chunk sourceSpans size is invalid");
            }
        }
    }

    /** Chunk 在单个 Element 内的 UTF-16 范围，以及可选的全局 Artifact 范围。 */
    public record ElementRange(
            UUID elementId,
            int startOffset,
            int endOffset,
            Integer pageNumber,
            ArtifactRange artifactRange
    ) {
        /** Element 内偏移必须非空；全局范围由统一 Provenance 提供，不能推测。 */
        public ElementRange {
            Objects.requireNonNull(elementId, "elementId must not be null");
            if (startOffset < 0 || endOffset <= startOffset) {
                throw new IllegalArgumentException("element range must be non-empty");
            }
            if (pageNumber != null && pageNumber < 1) {
                throw new IllegalArgumentException("pageNumber must be positive");
            }
        }
    }

    /** 规范化文档 Artifact 内的 UTF-16 全局范围。 */
    public record ArtifactRange(
            UUID artifactId,
            int startOffset,
            int endOffset,
            Integer pageNumber
    ) {
        /** Artifact 标识和非空范围必须来自 Parser Provenance。 */
        public ArtifactRange {
            Objects.requireNonNull(artifactId, "artifactId must not be null");
            if (startOffset < 0 || endOffset <= startOffset) {
                throw new IllegalArgumentException("artifact range must be non-empty");
            }
            if (pageNumber != null && pageNumber < 1) {
                throw new IllegalArgumentException("pageNumber must be positive");
            }
        }
    }

    private static String boundedText(String value, int maximumLength, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds the hard limit");
        }
        return value;
    }

    private static String stableCode(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.strip();
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException(name + " is not a stable code");
        }
        return normalized;
    }
}
