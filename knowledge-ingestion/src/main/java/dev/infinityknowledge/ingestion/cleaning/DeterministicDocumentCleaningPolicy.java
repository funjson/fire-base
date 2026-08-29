package dev.infinityknowledge.ingestion.cleaning;

import dev.infinityknowledge.domain.document.ElementType;
import dev.infinityknowledge.domain.document.KnowledgeElement;
import dev.infinityknowledge.ingestion.cleaning.DocumentCleaningConfiguration.Action;
import dev.infinityknowledge.ingestion.parser.DocumentParseException;
import dev.infinityknowledge.ingestion.parser.ParsedDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 仅依据 Parser 明确角色和固定安全规则执行清洗的默认策略。
 *
 * <p>未知或缺失角色一律保留，以兼容现有 Parser 行为。角色匹配忽略大小写但不
 * 猜测正文语义；页码中的装饰符由 Parser 连同 {@code role=PAGE_NUMBER} 一起标注，
 * 本策略不会用正则扫描普通正文。</p>
 */
public final class DeterministicDocumentCleaningPolicy implements DocumentCleaningPolicy {

    /** 修改规则语义时必须递增，确保旧修订不会与新清洗结果共用处理指纹。 */
    public static final String VERSION = "deterministic-cleaning-v2";

    private static final String ROLE_ATTRIBUTE = "role";
    private static final String BLANK_CONTENT_REMOVED = "BLANK_CONTENT_REMOVED";
    private static final String HIDDEN_REMOVED = "HIDDEN_REMOVED";

    /**
     * 以固定字段顺序生成短且可读的契约，不依赖 Map 顺序或单次命中结果。
     */
    @Override
    public String contract(DocumentCleaningConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        return VERSION
                + ":h=" + configuration.header()
                + ",f=" + configuration.footer()
                + ",p=" + configuration.pageNumber()
                + ",w=" + configuration.watermark()
                + ",fm=" + configuration.frontMatter()
                + ",hidden=REMOVE,blank=REMOVE";
    }

    /**
     * 保持输入顺序，并在结构标题被排除时清理后续元素的章节路径。
     *
     * <p>元素标识、正文和页码、Bounding Box 等来源定位属性保持不变；
     * {@code METADATA_ONLY} 元素会增加稳定的治理去向与原因码。</p>
     */
    @Override
    public DocumentCleaningResult clean(
            ParsedDocument document,
            DocumentCleaningConfiguration configuration
    ) {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");

        List<KnowledgeElement> indexable = new ArrayList<>(document.elements().size());
        List<KnowledgeElement> metadataOnly = new ArrayList<>();
        List<ElementCleaningDecision> decisions = new ArrayList<>(document.elements().size());
        Map<String, Integer> reasonCodeCounts = new LinkedHashMap<>();
        Map<List<String>, Action> structuralActions = new LinkedHashMap<>();
        Map<UUID, UUID> nearestRetainedAncestor = new LinkedHashMap<>();
        for (KnowledgeElement element : document.elements()) {
            Decision decision = decision(element, configuration);
            UUID retainedParentId = retainedParentId(element, nearestRetainedAncestor);
            if (isStructural(element)) {
                structuralActions.put(structuralPath(element), decision.action());
            }
            if (decision.reasonCode() != null) {
                increment(reasonCodeCounts, decision.reasonCode());
            }
            List<String> sectionPath = cleanedSectionPath(
                    element.sectionPath(),
                    structuralActions
            );
            switch (decision.action()) {
                case KEEP -> indexable.add(withStructure(
                        element,
                        retainedParentId,
                        sectionPath
                ));
                case REMOVE -> {
                    // 仅记录原因码，避免清洗结果和日志继续携带已排除正文。
                }
                case METADATA_ONLY -> metadataOnly.add(metadataOnlyElement(
                        element,
                        retainedParentId,
                        sectionPath,
                        decision.reasonCode()
                ));
            }
            decisions.add(new ElementCleaningDecision(
                    element.id(),
                    element.type(),
                    element.attributes().get(ROLE_ATTRIBUTE),
                    decision.action(),
                    decision.reasonCode() == null ? "KEPT" : decision.reasonCode()
            ));
            nearestRetainedAncestor.put(
                    element.id(),
                    decision.action() == Action.REMOVE ? retainedParentId : element.id()
            );
        }
        return new DocumentCleaningResult(
                indexable,
                metadataOnly,
                reasonCodeCounts,
                contract(configuration),
                decisions
        );
    }

    private static Decision decision(
            KnowledgeElement element,
            DocumentCleaningConfiguration configuration
    ) {
        if (element.content().isBlank()) {
            return new Decision(Action.REMOVE, BLANK_CONTENT_REMOVED);
        }
        ElementRole role = roleOf(element);
        if (role == ElementRole.HIDDEN) {
            return new Decision(Action.REMOVE, HIDDEN_REMOVED);
        }
        Action configured = actionFor(role, configuration);
        if (configured == null) {
            return new Decision(Action.KEEP, null);
        }
        return new Decision(
                configured,
                role.name() + '_' + reasonSuffix(configured)
        );
    }

    private static boolean isStructural(KnowledgeElement element) {
        return element.type() == ElementType.HEADING || element.type() == ElementType.TITLE;
    }

    /**
     * 以 Parser 给出的原始层级路径作为结构节点键。
     *
     * <p>部分外部 Parser 不会把当前 TITLE/HEADING 放入自身路径，
     * 因此在末尾缺失时补入标题正文，使后续元素仍能命中同一节点。</p>
     */
    private static List<String> structuralPath(KnowledgeElement element) {
        List<String> path = new ArrayList<>(element.sectionPath());
        if (path.isEmpty() || !path.getLast().equals(element.content())) {
            path.add(element.content());
        }
        return List.copyOf(path);
    }

    /**
     * 按原始路径前缀的最新治理决定过滤结构节点。
     *
     * <p>使用完整前缀而不是标题文字集合，可区分不同分支下的同名标题；
     * 同路径的后续结构元素会覆盖旧决定，避免影响后来的同名章节。</p>
     */
    private static List<String> cleanedSectionPath(
            List<String> original,
            Map<List<String>, Action> structuralActions
    ) {
        if (original.isEmpty() || structuralActions.isEmpty()) {
            return original;
        }
        List<String> prefix = new ArrayList<>(original.size());
        List<String> cleaned = new ArrayList<>(original.size());
        for (String segment : original) {
            prefix.add(segment);
            Action action = structuralActions.get(List.copyOf(prefix));
            if (action == null || action == Action.KEEP) {
                cleaned.add(segment);
            }
        }
        return Collections.unmodifiableList(cleaned);
    }

    /**
     * 把被删除父节点的子元素重连到最近仍持久化的祖先。
     *
     * <p>{@code METADATA_ONLY} 元素仍会写入结构库，因此可继续作为父节点。
     * Parser 必须在子元素前输出父元素；否则在 Cleaner 边界稳定失败，
     * 避免带着悬空外键进入数据库。</p>
     */
    private static UUID retainedParentId(
            KnowledgeElement element,
            Map<UUID, UUID> nearestRetainedAncestor
    ) {
        if (nearestRetainedAncestor.containsKey(element.id())) {
            throw new DocumentParseException("parser output contains a duplicate element id");
        }
        if (element.parentId() == null) {
            return null;
        }
        if (!nearestRetainedAncestor.containsKey(element.parentId())) {
            throw new DocumentParseException(
                    "element parent must precede its child in parser output"
            );
        }
        return nearestRetainedAncestor.get(element.parentId());
    }

    private static KnowledgeElement withStructure(
            KnowledgeElement element,
            UUID parentId,
            List<String> sectionPath
    ) {
        if (Objects.equals(element.parentId(), parentId)
                && element.sectionPath().equals(sectionPath)) {
            return element;
        }
        return copy(element, parentId, sectionPath, element.attributes());
    }

    private static KnowledgeElement metadataOnlyElement(
            KnowledgeElement element,
            UUID parentId,
            List<String> sectionPath,
            String reasonCode
    ) {
        Map<String, String> attributes = new LinkedHashMap<>(element.attributes());
        attributes.put(
                CLEANING_DISPOSITION_ATTRIBUTE,
                Action.METADATA_ONLY.name()
        );
        attributes.put(
                CLEANING_REASON_CODE_ATTRIBUTE,
                Objects.requireNonNull(reasonCode, "reasonCode must not be null")
        );
        return copy(element, parentId, sectionPath, attributes);
    }

    private static KnowledgeElement copy(
            KnowledgeElement element,
            UUID parentId,
            List<String> sectionPath,
            Map<String, String> attributes
    ) {
        return new KnowledgeElement(
                element.id(),
                element.revisionId(),
                parentId,
                element.type(),
                element.ordinal(),
                sectionPath,
                element.content(),
                attributes
        );
    }

    private static ElementRole roleOf(KnowledgeElement element) {
        String value = element.attributes().get(ROLE_ATTRIBUTE);
        if (value == null || value.isBlank()) {
            return ElementRole.UNCLASSIFIED;
        }
        try {
            return ElementRole.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ElementRole.UNCLASSIFIED;
        }
    }

    private static Action actionFor(
            ElementRole role,
            DocumentCleaningConfiguration configuration
    ) {
        return switch (role) {
            case HEADER -> configuration.header();
            case FOOTER -> configuration.footer();
            case PAGE_NUMBER -> configuration.pageNumber();
            case WATERMARK -> configuration.watermark();
            case FRONT_MATTER -> configuration.frontMatter();
            case HIDDEN, UNCLASSIFIED -> null;
        };
    }

    private static String reasonSuffix(Action action) {
        return switch (action) {
            case KEEP -> "KEPT";
            case REMOVE -> "REMOVED";
            case METADATA_ONLY -> "METADATA_ONLY";
        };
    }

    private static void increment(Map<String, Integer> counts, String reasonCode) {
        counts.merge(reasonCode, 1, Integer::sum);
    }

    private enum ElementRole {
        HEADER,
        FOOTER,
        PAGE_NUMBER,
        WATERMARK,
        FRONT_MATTER,
        HIDDEN,
        UNCLASSIFIED
    }

    private record Decision(Action action, String reasonCode) {
    }
}
