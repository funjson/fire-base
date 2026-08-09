package dev.infinityknowledge.domain.document;

import dev.infinityknowledge.domain.common.DomainChecks;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示文档修订中的一个结构化元素。
 *
 * @param id 元素标识
 * @param revisionId 修订标识
 * @param parentId 父元素标识，根元素为 {@code null}
 * @param type 元素类型
 * @param ordinal 同级稳定顺序
 * @param sectionPath 从根标题到当前元素的路径
 * @param content 元素正文
 * @param attributes 解析器产生的非敏感属性
 */
public record KnowledgeElement(
        UUID id,
        UUID revisionId,
        UUID parentId,
        ElementType type,
        int ordinal,
        List<String> sectionPath,
        String content,
        Map<String, String> attributes
) {

    /**
     * 校验结构位置并复制路径和属性。
     */
    public KnowledgeElement {
        Objects.requireNonNull(id, "element id must not be null");
        Objects.requireNonNull(revisionId, "revisionId must not be null");
        Objects.requireNonNull(type, "element type must not be null");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
        sectionPath = List.copyOf(
                Objects.requireNonNull(sectionPath, "sectionPath must not be null")
        );
        content = DomainChecks.requiredText(content, "element content", 2_000_000);
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
    }
}

