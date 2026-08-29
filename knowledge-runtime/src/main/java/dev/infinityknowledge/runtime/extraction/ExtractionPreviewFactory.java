package dev.infinityknowledge.runtime.extraction;

import dev.infinityknowledge.domain.document.ElementProvenance;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.ingestion.cleaning.ElementCleaningDecision;
import dev.infinityknowledge.ingestion.extraction.ExtractionResult;
import dev.infinityknowledge.spi.extraction.ExtractionPreview;
import dev.infinityknowledge.spi.extraction.ExtractionPreview.ArtifactRange;
import dev.infinityknowledge.spi.extraction.ExtractionPreview.ElementRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 从真实抽取结果生成有界、可追溯且不包含 contextualText 的页面预览。 */
public final class ExtractionPreviewFactory {

    private static final int ELEMENT_TEXT_BUDGET = ExtractionPreview.MAX_TOTAL_TEXT * 2 / 5;
    private static final int CHUNK_TEXT_BUDGET =
            ExtractionPreview.MAX_TOTAL_TEXT - ELEMENT_TEXT_BUDGET;

    /**
     * 复制有限数量的 Element、Chunk 和正文片段。
     *
     * <p>Artifact 全文不会进入预览；SourceSpan 只保存数值范围。文本截断保持 UTF-16
     * 代理对完整，避免页面收到损坏 Emoji。任何正文都不得由调用方写入日志。</p>
     */
    public ExtractionPreview create(ExtractionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Map<UUID, ElementProvenance> provenance = indexProvenance(result);
        Map<UUID, ElementCleaningDecision> decisions = indexDecisions(result);
        Map<UUID, KnowledgeElement> elementsById = indexElements(result);

        List<ExtractionPreview.Element> elements = new ArrayList<>();
        int remainingElementText = ELEMENT_TEXT_BUDGET;
        boolean truncated = result.retainedElements().size() > ExtractionPreview.MAX_ELEMENTS
                || result.chunks().size() > ExtractionPreview.MAX_CHUNKS;
        for (KnowledgeElement element : result.retainedElements().stream()
                .limit(ExtractionPreview.MAX_ELEMENTS)
                .toList()) {
            ElementProvenance source = require(provenance, element.id(), "provenance");
            ElementCleaningDecision decision = require(decisions, element.id(), "decision");
            TextPreview text = text(
                    element.content(),
                    ExtractionPreview.MAX_TEXT_PER_ELEMENT,
                    remainingElementText
            );
            remainingElementText -= text.value().length();
            truncated |= text.truncated();
            elements.add(new ExtractionPreview.Element(
                    element.id(),
                    element.parentId(),
                    element.type(),
                    element.ordinal(),
                    element.sectionPath(),
                    element.attributes().get("role"),
                    decision.action().name(),
                    decision.reasonCode(),
                    artifactRange(source),
                    element.content().length(),
                    text.value(),
                    text.truncated()
            ));
        }

        List<ExtractionPreview.Chunk> chunks = new ArrayList<>();
        int remainingChunkText = CHUNK_TEXT_BUDGET;
        for (var chunk : result.chunks().stream()
                .limit(ExtractionPreview.MAX_CHUNKS)
                .toList()) {
            TextPreview text = text(
                    chunk.content(),
                    ExtractionPreview.MAX_TEXT_PER_CHUNK,
                    remainingChunkText
            );
            remainingChunkText -= text.value().length();
            truncated |= text.truncated();
            if (chunk.sourceSpans().size()
                    > ExtractionPreview.MAX_SOURCE_SPANS_PER_CHUNK) {
                truncated = true;
            }
            List<ElementRange> spans = chunk.sourceSpans().stream()
                    .limit(ExtractionPreview.MAX_SOURCE_SPANS_PER_CHUNK)
                    .map(span -> {
                        KnowledgeElement element = require(
                                elementsById,
                                span.elementId(),
                                "element"
                        );
                        ElementProvenance source = require(
                                provenance,
                                span.elementId(),
                                "provenance"
                        );
                        if (span.endOffset() > element.content().length()) {
                            throw new IllegalArgumentException(
                                    "chunk source span exceeds element content"
                            );
                        }
                        ArtifactRange absolute = new ArtifactRange(
                                source.artifactId(),
                                source.startOffset() + span.startOffset(),
                                source.startOffset() + span.endOffset(),
                                source.pageNumber()
                        );
                        return new ElementRange(
                                span.elementId(),
                                span.startOffset(),
                                span.endOffset(),
                                span.pageNumber(),
                                absolute
                        );
                    }).toList();
            chunks.add(new ExtractionPreview.Chunk(
                    chunk.id(),
                    chunk.ordinal(),
                    chunk.sectionPath(),
                    chunk.content().length(),
                    text.value(),
                    text.truncated(),
                    spans
            ));
        }

        return new ExtractionPreview(
                result.artifact().id(),
                result.artifact().text().length(),
                result.retainedElements().size(),
                result.chunks().size(),
                truncated,
                elements,
                chunks
        );
    }

    private static Map<UUID, ElementProvenance> indexProvenance(ExtractionResult result) {
        Map<UUID, ElementProvenance> values = new HashMap<>();
        for (ElementProvenance value : result.elementProvenance()) {
            if (!result.artifact().id().equals(value.artifactId())
                    || values.putIfAbsent(value.elementId(), value) != null) {
                throw new IllegalArgumentException("extraction provenance is inconsistent");
            }
        }
        return Map.copyOf(values);
    }

    private static Map<UUID, ElementCleaningDecision> indexDecisions(
            ExtractionResult result
    ) {
        Map<UUID, ElementCleaningDecision> values = new HashMap<>();
        for (ElementCleaningDecision value : result.cleaningDecisions()) {
            if (values.putIfAbsent(value.elementId(), value) != null) {
                throw new IllegalArgumentException("extraction decisions contain duplicates");
            }
        }
        return Map.copyOf(values);
    }

    private static Map<UUID, KnowledgeElement> indexElements(ExtractionResult result) {
        Map<UUID, KnowledgeElement> values = new HashMap<>();
        for (KnowledgeElement value : result.retainedElements()) {
            if (values.putIfAbsent(value.id(), value) != null) {
                throw new IllegalArgumentException("extraction elements contain duplicates");
            }
        }
        return Map.copyOf(values);
    }

    private static ArtifactRange artifactRange(ElementProvenance value) {
        return new ArtifactRange(
                value.artifactId(),
                value.startOffset(),
                value.endOffset(),
                value.pageNumber()
        );
    }

    private static TextPreview text(String value, int perItemLimit, int remainingBudget) {
        int maximum = Math.min(perItemLimit, Math.max(0, remainingBudget));
        int end = Math.min(value.length(), maximum);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return new TextPreview(value.substring(0, end), end < value.length());
    }

    private static <T> T require(Map<UUID, T> values, UUID id, String name) {
        T value = values.get(id);
        if (value == null) {
            throw new IllegalArgumentException("extraction result is missing " + name);
        }
        return value;
    }

    private record TextPreview(String value, boolean truncated) {
    }
}
