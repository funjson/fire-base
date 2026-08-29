package dev.infinityknowledge.parser.docling;

import ai.docling.core.DoclingDocument;
import ai.docling.core.DoclingDocument.BaseTextItem;
import ai.docling.core.DoclingDocument.CodeItem;
import ai.docling.core.DoclingDocument.ContentLayer;
import ai.docling.core.DoclingDocument.DocItemLabel;
import ai.docling.core.DoclingDocument.GroupItem;
import ai.docling.core.DoclingDocument.ListItem;
import ai.docling.core.DoclingDocument.ProvenanceItem;
import ai.docling.core.DoclingDocument.RefItem;
import ai.docling.core.DoclingDocument.SectionHeaderItem;
import ai.docling.core.DoclingDocument.TableCell;
import ai.docling.core.DoclingDocument.TableItem;
import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.DocumentParseInput;
import dev.infinityknowledge.ingestion.parser.ParsedDocument;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把 Docling lossless JSON 严格映射为平台标准元素。 */
final class DoclingDocumentMapper {

    private static final Pattern ARRAY_REF = Pattern.compile(
            "^#/(texts|tables|groups|pictures|key_value_items|form_items)/(0|[1-9][0-9]*)$"
    );
    private static final int MAXIMUM_ELEMENT_CHARACTERS = 1_900_000;

    private final DoclingServerContract contract;

    DoclingDocumentMapper(DoclingServerContract contract) {
        this.contract = contract;
    }

    /**
     * 严格按 {@code body.children} 的引用顺序遍历，并验证所有可达引用。
     *
     * <p>Group 只承载 Docling 内部层级，不会伪造平台元素；标题层级会转换为
     * sectionPath 和 parentId，原始 parent/self_ref 同时保留在 attributes 便于诊断。</p>
     */
    ParsedDocument map(
            String parserId,
            String parserVersion,
            DocumentParseInput input,
            DoclingDocument document,
            boolean requirePageNumbers
    ) {
        requireSchema(document);
        validatePageBudget(document, input);
        ReferenceIndex index = ReferenceIndex.create(document);
        MappingState state = new MappingState(input, requirePageNumbers);
        state.traverseChildren(document.getBody(), index);
        index.requireCompleteBodyTraversal(state.emittedRefs, state.traversedGroups);
        if (state.elements.isEmpty()) {
            throw invalid("lossless JSON did not contain indexable body elements");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("doclingSchemaName", document.getSchemaName());
        attributes.put("doclingSchemaVersion", document.getVersion());
        attributes.put("doclingServerContract", contract.value());
        if (document.getName() != null && !document.getName().isBlank()) {
            attributes.put("doclingDocumentName", document.getName().strip());
        }
        if (document.getPages() != null) {
            attributes.put("pages", Integer.toString(document.getPages().size()));
        }
        return new ParsedDocument(
                parserId,
                parserVersion,
                state.elements,
                attributes
        );
    }

    private void requireSchema(DoclingDocument document) {
        if (document == null) {
            throw invalid("json_content was null");
        }
        if (!contract.schemaName().equals(document.getSchemaName())
                || !contract.schemaVersion().equals(document.getVersion())) {
            throw new DocumentParseException(
                    "DOCLING_SCHEMA_MISMATCH: lossless JSON contract did not match deployment"
            );
        }
        if (document.getBody() == null || document.getBody().getChildren() == null) {
            throw invalid("lossless JSON body was incomplete");
        }
    }

    private static void validatePageBudget(
            DoclingDocument document,
            DocumentParseInput input
    ) {
        if (document.getPages() != null
                && document.getPages().size() > input.limits().maximumPages()) {
            throw new DocumentParseException("DOCLING_PAGE_LIMIT: document exceeds maximumPages");
        }
    }

    private static DocumentParseException invalid(String detail) {
        return new DocumentParseException("DOCLING_RESPONSE_INVALID: " + detail);
    }

    /** 遍历期状态；一个实例只处理一个不可变修订。 */
    private static final class MappingState {

        private final DocumentParseInput input;
        private final boolean requirePageNumbers;
        private final List<KnowledgeElement> elements = new ArrayList<>();
        private final List<Heading> headings = new ArrayList<>();
        private final Map<String, UUID> emittedIds = new HashMap<>();
        private final Set<String> emittedRefs = new HashSet<>();
        private final Set<String> traversedGroups = new HashSet<>();
        private final Set<String> activeGroups = new HashSet<>();
        private int textCharacters;

        private MappingState(DocumentParseInput input, boolean requirePageNumbers) {
            this.input = input;
            this.requirePageNumbers = requirePageNumbers;
        }

        private void traverseChildren(GroupItem group, ReferenceIndex index) {
            for (RefItem child : group.getChildren()) {
                if (child == null || child.getRef() == null || child.getRef().isBlank()) {
                    throw invalid("body contained an empty child reference");
                }
                traverse(child.getRef(), index);
            }
        }

        private void traverse(String ref, ReferenceIndex index) {
            ReferencedNode node = index.resolve(ref);
            switch (node.kind) {
                case GROUP -> traverseGroup(ref, (GroupItem) node.value, index);
                case TEXT -> appendText(ref, (BaseTextItem) node.value, index);
                case TABLE -> appendTable(ref, (TableItem) node.value, index);
                case NON_TEXT -> throw invalid("body contained an unsupported non-text item");
            }
        }

        private void traverseGroup(String ref, GroupItem group, ReferenceIndex index) {
            if (!activeGroups.add(ref)) {
                throw invalid("group references contain a cycle");
            }
            if (!traversedGroups.add(ref)) {
                throw invalid("body referenced a group more than once");
            }
            if (group.getChildren() == null) {
                throw invalid("group children were missing");
            }
            traverseChildren(group, index);
            activeGroups.remove(ref);
        }

        private void appendText(
                String ref,
                BaseTextItem item,
                ReferenceIndex index
        ) {
            requireFirstEmission(ref);
            String content = normalizedContent(item.getText());
            if (content.isEmpty()) {
                traverseChildren(item.getChildren(), index);
                return;
            }
            ElementType type = elementType(item.getLabel());
            int headingLevel = headingLevel(item, type);
            UUID id = elementId(input.revisionId(), ref);
            UUID parentId;
            List<String> sectionPath;
            if (type == ElementType.HEADING) {
                while (!headings.isEmpty() && headings.getLast().level >= headingLevel) {
                    headings.removeLast();
                }
                parentId = headings.isEmpty() ? null : headings.getLast().id;
                sectionPath = new ArrayList<>(headings.stream().map(value -> value.title).toList());
                sectionPath.add(content);
            } else {
                parentId = resolvedParent(item.getParent(), index);
                if (parentId == null && !headings.isEmpty()) {
                    parentId = headings.getLast().id;
                }
                sectionPath = headings.stream().map(value -> value.title).toList();
            }
            Map<String, String> attributes = baseAttributes(
                    ref,
                    item.getParent(),
                    item.getLabel() == null ? "unknown" : item.getLabel().name(),
                    item.getProv()
            );
            if (headingLevel > 0) {
                attributes.put("headingLevel", Integer.toString(headingLevel));
            }
            if (item instanceof ListItem listItem) {
                attributes.put("enumerated", Boolean.toString(listItem.isEnumerated()));
                if (listItem.getMarker() != null && !listItem.getMarker().isBlank()) {
                    attributes.put("marker", listItem.getMarker().strip());
                }
            }
            if (item instanceof CodeItem codeItem
                    && codeItem.getCodeLanguage() != null
                    && !codeItem.getCodeLanguage().isBlank()) {
                attributes.put("codeLanguage", codeItem.getCodeLanguage().strip());
            }
            append(id, parentId, type, sectionPath, content, attributes);
            emittedIds.put(ref, id);
            if (type == ElementType.HEADING) {
                headings.add(new Heading(headingLevel, id, content));
            }
            traverseChildren(item.getChildren(), index);
        }

        private void appendTable(String ref, TableItem table, ReferenceIndex index) {
            requireFirstEmission(ref);
            String content = tableText(table);
            UUID id = elementId(input.revisionId(), ref);
            UUID parentId = resolvedParent(table.getParent(), index);
            if (parentId == null && !headings.isEmpty()) {
                parentId = headings.getLast().id;
            }
            Map<String, String> attributes = baseAttributes(
                    ref,
                    table.getParent(),
                    table.getLabel() == null ? "table" : table.getLabel(),
                    table.getProv()
            );
            append(
                    id,
                    parentId,
                    ElementType.TABLE,
                    headings.stream().map(value -> value.title).toList(),
                    content,
                    attributes
            );
            emittedIds.put(ref, id);
            traverseChildren(table.getChildren(), index);
        }

        /**
         * v1.10 的 reading order 是树；Text/Table 的 children 必须与 Group 使用同一套
         * 引用校验，不能只遍历根 {@code body.children}。
         */
        private void traverseChildren(List<RefItem> children, ReferenceIndex index) {
            if (children == null) {
                throw invalid("indexable item children were missing");
            }
            for (RefItem child : children) {
                if (child == null || child.getRef() == null || child.getRef().isBlank()) {
                    throw invalid("indexable item contained an empty child reference");
                }
                traverse(child.getRef(), index);
            }
        }

        private void append(
                UUID id,
                UUID parentId,
                ElementType type,
                List<String> sectionPath,
                String content,
                Map<String, String> attributes
        ) {
            if (elements.size() >= input.limits().maximumElements()) {
                throw new DocumentParseException(
                        "DOCLING_ELEMENT_LIMIT: document exceeds maximumElements"
                );
            }
            if (content.length() > MAXIMUM_ELEMENT_CHARACTERS) {
                throw new DocumentParseException(
                        "DOCLING_ELEMENT_TOO_LARGE: one element exceeds the platform limit"
                );
            }
            if ((long) textCharacters + content.length()
                    > input.limits().maximumTextCharacters()) {
                throw new DocumentParseException(
                        "DOCLING_TEXT_LIMIT: document exceeds maximumTextCharacters"
                );
            }
            elements.add(new KnowledgeElement(
                    id,
                    input.revisionId(),
                    parentId,
                    type,
                    elements.size(),
                    sectionPath,
                    content,
                    attributes
            ));
            textCharacters += content.length();
        }

        private Map<String, String> baseAttributes(
                String selfRef,
                RefItem parent,
                String label,
                List<ProvenanceItem> provenance
        ) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("doclingSelfRef", selfRef);
            attributes.put("doclingLabel", label.toLowerCase(Locale.ROOT));
            if (parent != null && parent.getRef() != null && !parent.getRef().isBlank()) {
                attributes.put("doclingParentRef", parent.getRef());
            }
            PageRange pages = pageRange(provenance);
            if (pages.first != null) {
                attributes.put("pageNumber", Integer.toString(pages.first));
                if (!pages.first.equals(pages.last)) {
                    attributes.put("endPageNumber", Integer.toString(pages.last));
                }
            } else if (requirePageNumbers) {
                throw invalid("PDF body element did not contain page provenance");
            }
            return attributes;
        }

        private PageRange pageRange(List<ProvenanceItem> provenance) {
            Integer first = null;
            Integer last = null;
            if (provenance != null) {
                for (ProvenanceItem item : provenance) {
                    if (item == null || item.getPageNo() == null) {
                        continue;
                    }
                    int page = item.getPageNo();
                    if (page < 1 || page > input.limits().maximumPages()) {
                        throw invalid("page provenance was outside the configured budget");
                    }
                    first = first == null ? page : Math.min(first, page);
                    last = last == null ? page : Math.max(last, page);
                }
            }
            return new PageRange(first, last);
        }

        private UUID resolvedParent(RefItem parent, ReferenceIndex index) {
            if (parent == null || parent.getRef() == null) {
                return null;
            }
            String parentRef = parent.getRef();
            if ("#/body".equals(parentRef)) {
                return null;
            }
            ReferencedNode parentNode = index.resolve(parentRef);
            if (parentNode.kind == NodeKind.GROUP) {
                return null;
            }
            if (parentNode.kind == NodeKind.NON_TEXT) {
                throw invalid("element parent referenced an unsupported non-text item");
            }
            UUID parentId = emittedIds.get(parentRef);
            if (parentId == null) {
                throw invalid("indexable parent had not been emitted before its child");
            }
            return parentId;
        }

        private void requireFirstEmission(String ref) {
            if (!emittedRefs.add(ref)) {
                throw invalid("body referenced an element more than once");
            }
        }

        private static String tableText(TableItem table) {
            if (table.getData() == null || table.getData().getGrid() == null
                    || table.getData().getGrid().isEmpty()) {
                throw invalid("table did not contain the requested structure grid");
            }
            List<String> rows = new ArrayList<>();
            for (List<TableCell> row : table.getData().getGrid()) {
                if (row == null) {
                    throw invalid("table grid contained a null row");
                }
                List<String> cells = new ArrayList<>();
                for (TableCell cell : row) {
                    if (cell == null) {
                        throw invalid("table grid contained a null cell");
                    }
                    cells.add(cell.getText() == null ? "" : cell.getText().strip());
                }
                rows.add(String.join("\t", cells).stripTrailing());
            }
            String text = String.join("\n", rows).strip();
            if (text.isEmpty()) {
                throw invalid("table grid did not contain text");
            }
            return text;
        }

        private static String normalizedContent(String value) {
            return value == null ? "" : value.strip();
        }

        private static ElementType elementType(DocItemLabel label) {
            if (label == null) {
                throw invalid("text item label was missing");
            }
            return switch (label) {
                case TITLE -> ElementType.TITLE;
                case SECTION_HEADER -> ElementType.HEADING;
                case LIST_ITEM -> ElementType.LIST;
                case CODE -> ElementType.CODE;
                default -> ElementType.PARAGRAPH;
            };
        }

        private static int headingLevel(BaseTextItem item, ElementType type) {
            if (type != ElementType.HEADING) {
                return 0;
            }
            if (!(item instanceof SectionHeaderItem header)
                    || header.getLevel() == null
                    || header.getLevel() < 1
                    || header.getLevel() > 6) {
                throw invalid("section header level must be between 1 and 6");
            }
            return header.getLevel();
        }

        private static UUID elementId(UUID revisionId, String ref) {
            return UUID.nameUUIDFromBytes(
                    (revisionId + ":docling:" + ref).getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    /** 构建并验证 Docling 数组下标引用。 */
    private static final class ReferenceIndex {

        private final DoclingDocument document;

        private ReferenceIndex(DoclingDocument document) {
            this.document = document;
        }

        private static ReferenceIndex create(DoclingDocument document) {
            verifySelfRefs("texts", safe(document.getTexts()), BaseTextItem::getSelfRef);
            verifySelfRefs("tables", safe(document.getTables()), TableItem::getSelfRef);
            verifySelfRefs("groups", safe(document.getGroups()), GroupItem::getSelfRef);
            verifySelfRefs("pictures", safe(document.getPictures()), value -> value.getSelfRef());
            verifySelfRefs(
                    "key_value_items",
                    safe(document.getKeyValueItems()),
                    value -> value.getSelfRef()
            );
            verifySelfRefs("form_items", safe(document.getFormItems()), value -> value.getSelfRef());
            return new ReferenceIndex(document);
        }

        private ReferencedNode resolve(String ref) {
            Matcher matcher = ARRAY_REF.matcher(ref);
            if (!matcher.matches()) {
                throw invalid("unsupported or malformed reference");
            }
            int index;
            try {
                index = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException failure) {
                throw invalid("reference index exceeded the supported range");
            }
            return switch (matcher.group(1)) {
                case "texts" -> new ReferencedNode(
                        NodeKind.TEXT,
                        at(safe(document.getTexts()), index)
                );
                case "tables" -> new ReferencedNode(
                        NodeKind.TABLE,
                        at(safe(document.getTables()), index)
                );
                case "groups" -> new ReferencedNode(
                        NodeKind.GROUP,
                        at(safe(document.getGroups()), index)
                );
                case "pictures" -> new ReferencedNode(
                        NodeKind.NON_TEXT,
                        at(safe(document.getPictures()), index)
                );
                case "key_value_items" -> new ReferencedNode(
                        NodeKind.NON_TEXT,
                        at(safe(document.getKeyValueItems()), index)
                );
                case "form_items" -> new ReferencedNode(
                        NodeKind.NON_TEXT,
                        at(safe(document.getFormItems()), index)
                );
                default -> throw invalid("reference collection was unsupported");
            };
        }

        /**
         * 验证 BODY 层的已知节点全部且仅一次出现在 reading-order 树中。
         *
         * <p>Docling 数组不是阅读顺序；但数组中存在而从根树不可达的 BODY 节点代表
         * Schema/客户端漂移或上游不完整响应，必须失败，不能静默截断后发布。</p>
         */
        private void requireCompleteBodyTraversal(
                Set<String> emittedRefs,
                Set<String> traversedGroups
        ) {
            requireConsumedBodyItems(
                    safe(document.getTexts()),
                    BaseTextItem::getSelfRef,
                    BaseTextItem::getContentLayer,
                    emittedRefs,
                    "text"
            );
            requireConsumedBodyItems(
                    safe(document.getTables()),
                    TableItem::getSelfRef,
                    TableItem::getContentLayer,
                    emittedRefs,
                    "table"
            );
            requireConsumedBodyItems(
                    safe(document.getGroups()),
                    GroupItem::getSelfRef,
                    GroupItem::getContentLayer,
                    traversedGroups,
                    "group"
            );
            requireNoUnreachableBodyItems(
                    safe(document.getPictures()),
                    value -> value.getContentLayer()
            );
            requireNoUnreachableBodyItems(
                    safe(document.getKeyValueItems()),
                    value -> value.getContentLayer()
            );
            requireNoUnreachableBodyItems(
                    safe(document.getFormItems()),
                    value -> value.getContentLayer()
            );
        }

        private static <T> void requireConsumedBodyItems(
                List<T> values,
                java.util.function.Function<T, String> selfRef,
                java.util.function.Function<T, ContentLayer> contentLayer,
                Set<String> consumed,
                String kind
        ) {
            for (T value : values) {
                ContentLayer layer = contentLayer.apply(value);
                if (layer == null) {
                    throw invalid(kind + " item content layer was missing");
                }
                if (layer == ContentLayer.BODY && !consumed.contains(selfRef.apply(value))) {
                    throw invalid("BODY " + kind + " item was unreachable from reading order");
                }
            }
        }

        private static <T> void requireNoUnreachableBodyItems(
                List<T> values,
                java.util.function.Function<T, ContentLayer> contentLayer
        ) {
            for (T value : values) {
                ContentLayer layer = contentLayer.apply(value);
                if (layer == null) {
                    throw invalid("non-text item content layer was missing");
                }
                if (layer == ContentLayer.BODY) {
                    throw invalid("body contained an unsupported non-text item");
                }
            }
        }

        private static <T> void verifySelfRefs(
                String collection,
                List<T> values,
                java.util.function.Function<T, String> selfRef
        ) {
            for (int index = 0; index < values.size(); index++) {
                T value = values.get(index);
                if (value == null) {
                    throw invalid(collection + " contained a null item");
                }
                String expected = "#/" + collection + '/' + index;
                if (!expected.equals(selfRef.apply(value))) {
                    throw invalid(collection + " self_ref did not match its array position");
                }
            }
        }

        private static <T> T at(List<T> values, int index) {
            if (index < 0 || index >= values.size()) {
                throw invalid("reference target did not exist");
            }
            return values.get(index);
        }

        private static <T> List<T> safe(List<T> values) {
            return values == null ? List.of() : values;
        }

    }

    private enum NodeKind {
        GROUP,
        TEXT,
        TABLE,
        NON_TEXT
    }

    private static final class ReferencedNode {
        private final NodeKind kind;
        private final Object value;

        private ReferencedNode(NodeKind kind, Object value) {
            this.kind = kind;
            this.value = value;
        }
    }

    private static final class Heading {
        private final int level;
        private final UUID id;
        private final String title;

        private Heading(int level, UUID id, String title) {
            this.level = level;
            this.id = id;
            this.title = title;
        }
    }

    private static final class PageRange {
        private final Integer first;
        private final Integer last;

        private PageRange(Integer first, Integer last) {
            this.first = first;
            this.last = last;
        }
    }
}
