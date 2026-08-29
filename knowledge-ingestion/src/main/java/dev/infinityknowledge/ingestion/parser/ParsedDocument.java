package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementProvenance;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.document.NormalizedDocumentArtifact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 可直接交给 Chunker 的结构化 Parser 输出。
 *
 * @param parserId 稳定的 Parser 实现标识
 * @param parserVersion 写入修订指纹的 Parser 契约版本
 * @param elements 按来源阅读顺序排列的结构元素；有 parentId 时父元素必须先于子元素
 * @param attributes 不含敏感正文的文档级属性
 * @param artifact Parser 输出的规范化文本制品，所有来源范围共享其坐标空间
 * @param provenance 与 elements 一一对应的精确制品范围
 */
public record ParsedDocument(
        String parserId,
        String parserVersion,
        List<KnowledgeElement> elements,
        Map<String, String> attributes,
        NormalizedDocumentArtifact artifact,
        List<ElementProvenance> provenance
) {

    /** 防御性复制 Parser 输出，并验证 Element 正文与制品范围完全一致。 */
    public ParsedDocument {
        Objects.requireNonNull(parserId, "parserId must not be null");
        Objects.requireNonNull(parserVersion, "parserVersion must not be null");
        elements = List.copyOf(Objects.requireNonNull(elements, "elements must not be null"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        Objects.requireNonNull(artifact, "artifact must not be null");
        provenance = List.copyOf(Objects.requireNonNull(
                provenance,
                "provenance must not be null"
        ));
        validateProvenance(elements, artifact, provenance);
    }

    /**
     * 为只输出结构元素的 Parser 建立确定性的阅读顺序制品。
     *
     * <p>该构造不会伪造原文件偏移；它把 Element 正文按两个换行拼接为独立的
     * 规范化制品，适用于 PDF、DOCX、HTML 和外部 Parser 的统一验收坐标。</p>
     */
    public ParsedDocument(
            String parserId,
            String parserVersion,
            List<KnowledgeElement> elements,
            Map<String, String> attributes
    ) {
        this(parserId, parserVersion, elements, attributes, materialize(elements));
    }

    private ParsedDocument(
            String parserId,
            String parserVersion,
            List<KnowledgeElement> elements,
            Map<String, String> attributes,
            MaterializedArtifact materialized
    ) {
        this(
                parserId,
                parserVersion,
                elements,
                attributes,
                materialized.artifact(),
                materialized.provenance()
        );
    }

    /** 按标识查找来源；缺失表示 Parser 违反统一来源契约。 */
    public ElementProvenance requireProvenance(UUID elementId) {
        Objects.requireNonNull(elementId, "elementId must not be null");
        return provenance.stream()
                .filter(value -> value.elementId().equals(elementId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "parsed document does not contain provenance for element"
                ));
    }

    private static void validateProvenance(
            List<KnowledgeElement> elements,
            NormalizedDocumentArtifact artifact,
            List<ElementProvenance> provenance
    ) {
        if (elements.size() != provenance.size()) {
            throw new IllegalArgumentException(
                    "every parsed element must have exactly one provenance range"
            );
        }
        Set<UUID> seen = new HashSet<>();
        for (int index = 0; index < elements.size(); index++) {
            KnowledgeElement element = elements.get(index);
            ElementProvenance source = provenance.get(index);
            if (!seen.add(source.elementId()) || !element.id().equals(source.elementId())) {
                throw new IllegalArgumentException(
                        "element provenance must be unique and follow element order"
                );
            }
            if (!artifact.id().equals(source.artifactId())
                    || source.endOffset() > artifact.text().length()) {
                throw new IllegalArgumentException(
                        "element provenance is outside the normalized artifact"
                );
            }
            String sourceText = artifact.text().substring(
                    source.startOffset(),
                    source.endOffset()
            );
            if (!element.content().equals(sourceText)) {
                throw new IllegalArgumentException(
                        "element content differs from its normalized artifact range"
                );
            }
        }
    }

    private static MaterializedArtifact materialize(List<KnowledgeElement> values) {
        List<KnowledgeElement> elements = List.copyOf(Objects.requireNonNull(
                values,
                "elements must not be null"
        ));
        StringBuilder text = new StringBuilder();
        List<int[]> ranges = new ArrayList<>(elements.size());
        for (KnowledgeElement element : elements) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            int start = text.length();
            text.append(element.content());
            ranges.add(new int[]{start, text.length()});
        }
        NormalizedDocumentArtifact artifact = NormalizedDocumentArtifact.create(
                "element-reading-order-v1",
                text.toString()
        );
        List<ElementProvenance> provenance = new ArrayList<>(elements.size());
        for (int index = 0; index < elements.size(); index++) {
            KnowledgeElement element = elements.get(index);
            int[] range = ranges.get(index);
            provenance.add(new ElementProvenance(
                    element.id(),
                    artifact.id(),
                    range[0],
                    range[1],
                    pageNumber(element)
            ));
        }
        return new MaterializedArtifact(artifact, List.copyOf(provenance));
    }

    /** 页码仅接受 Parser 已明确写入的正整数属性，无法确认时保持为空。 */
    private static Integer pageNumber(KnowledgeElement element) {
        String page = element.attributes().get(ElementProvenance.PAGE_NUMBER_ATTRIBUTE);
        if (page == null || !page.matches("[1-9][0-9]*")) {
            return null;
        }
        try {
            return Integer.valueOf(page);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 只在兼容构造期间传递一次制品及其来源范围。 */
    private record MaterializedArtifact(
            NormalizedDocumentArtifact artifact,
            List<ElementProvenance> provenance
    ) {
    }
}
