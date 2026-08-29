package dev.infinityknowledge.ingestion.parser;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.ElementProvenance;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.domain.document.NormalizedDocumentArtifact;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 在创建确定性知识元素时统一执行结构与文本预算。 */
final class ElementAccumulator {

    private static final int MAXIMUM_ELEMENT_CHARACTERS = 1_900_000;

    private final UUID revisionId;
    private final DocumentParseLimits limits;
    private final NormalizedDocumentArtifact artifact;
    private final List<KnowledgeElement> elements = new ArrayList<>();
    private final List<ElementProvenance> provenance = new ArrayList<>();
    private final List<String> headings = new ArrayList<>();
    private int textCharacters;

    ElementAccumulator(UUID revisionId, DocumentParseLimits limits) {
        this(revisionId, limits, null);
    }

    /** 创建绑定既有规范化文本制品的元素累积器。 */
    ElementAccumulator(
            UUID revisionId,
            DocumentParseLimits limits,
            NormalizedDocumentArtifact artifact
    ) {
        this.revisionId = revisionId;
        this.limits = limits;
        this.artifact = artifact;
    }

    /** 添加标题并更新后续元素使用的章节路径。 */
    void heading(int level, String title, Map<String, String> attributes) {
        if (level < 1 || level > 6) {
            throw new DocumentParseException("heading level must be between 1 and 6");
        }
        String normalized = normalize(title);
        if (normalized.isEmpty()) {
            return;
        }
        if (normalized.length() > 512) {
            throw new DocumentParseException("heading exceeds 512 characters");
        }
        while (headings.size() >= level) {
            headings.removeLast();
        }
        headings.add(normalized);
        add(ElementType.HEADING, normalized, attributes);
    }

    /** 从规范化制品的精确范围添加标题，并更新后续元素的章节路径。 */
    void headingFromArtifact(
            int level,
            int startOffset,
            int endOffset,
            Map<String, String> attributes
    ) {
        if (level < 1 || level > 6) {
            throw new DocumentParseException("heading level must be between 1 and 6");
        }
        String title = artifactText(startOffset, endOffset);
        if (title.isEmpty()) {
            return;
        }
        if (title.length() > 512) {
            throw new DocumentParseException("heading exceeds 512 characters");
        }
        while (headings.size() >= level) {
            headings.removeLast();
        }
        headings.add(title);
        addFromArtifact(ElementType.HEADING, startOffset, endOffset, attributes);
    }

    /** 添加正文，仅在领域对象的单元素安全上限要求时进行拆分。 */
    void add(ElementType type, String content, Map<String, String> attributes) {
        String normalized = normalize(content);
        if (normalized.isEmpty()) {
            return;
        }
        int offset = 0;
        while (offset < normalized.length()) {
            int end = Math.min(offset + MAXIMUM_ELEMENT_CHARACTERS, normalized.length());
            addSingle(type, normalized.substring(offset, end), attributes);
            offset = end;
        }
    }

    /**
     * 从制品精确范围添加正文；调用方必须先确定需要排除的语法符号和首尾空白。
     */
    void addFromArtifact(
            ElementType type,
            int startOffset,
            int endOffset,
            Map<String, String> attributes
    ) {
        String content = artifactText(startOffset, endOffset);
        if (content.isEmpty()) {
            return;
        }
        int contentOffset = 0;
        while (contentOffset < content.length()) {
            int contentEnd = Math.min(
                    contentOffset + MAXIMUM_ELEMENT_CHARACTERS,
                    content.length()
            );
            addSingleFromArtifact(
                    type,
                    content.substring(contentOffset, contentEnd),
                    attributes,
                    startOffset + contentOffset,
                    startOffset + contentEnd
            );
            contentOffset = contentEnd;
        }
    }

    /** 按来源阅读顺序返回不可变元素列表。 */
    List<KnowledgeElement> elements() {
        return List.copyOf(elements);
    }

    /** 使用当前元素和来源范围构造完整 Parser 输出。 */
    ParsedDocument parsedDocument(
            String parserId,
            String parserVersion,
            Map<String, String> attributes
    ) {
        if (artifact == null) {
            return new ParsedDocument(parserId, parserVersion, elements(), attributes);
        }
        return new ParsedDocument(
                parserId,
                parserVersion,
                elements(),
                attributes,
                artifact,
                provenance
        );
    }

    private void addSingle(ElementType type, String content, Map<String, String> attributes) {
        if (elements.size() >= limits.maximumElements()) {
            throw new DocumentParseException("document exceeds maximumElements");
        }
        if ((long) textCharacters + content.length() > limits.maximumTextCharacters()) {
            throw new DocumentParseException("document exceeds maximumTextCharacters");
        }
        int ordinal = elements.size();
        String identity = revisionId + ":" + ordinal + ':' + type + ':' + content;
        elements.add(new KnowledgeElement(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                revisionId,
                null,
                type,
                ordinal,
                List.copyOf(headings),
                content,
                new LinkedHashMap<>(attributes)
        ));
        textCharacters += content.length();
    }

    private void addSingleFromArtifact(
            ElementType type,
            String content,
            Map<String, String> attributes,
            int startOffset,
            int endOffset
    ) {
        addSingle(type, content, attributes);
        KnowledgeElement element = elements.getLast();
        provenance.add(new ElementProvenance(
                element.id(),
                artifact.id(),
                startOffset,
                endOffset,
                pageNumber(attributes)
        ));
    }

    private String artifactText(int startOffset, int endOffset) {
        if (artifact == null) {
            throw new IllegalStateException("element accumulator has no normalized artifact");
        }
        if (startOffset < 0 || endOffset <= startOffset
                || endOffset > artifact.text().length()) {
            throw new DocumentParseException("element range is outside normalized artifact");
        }
        return artifact.text().substring(startOffset, endOffset);
    }

    private static Integer pageNumber(Map<String, String> attributes) {
        String page = attributes.get(ElementProvenance.PAGE_NUMBER_ATTRIBUTE);
        if (page == null || !page.matches("[1-9][0-9]*")) {
            return null;
        }
        try {
            return Integer.valueOf(page);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
